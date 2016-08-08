package com.intellij.lang.javascript;

import com.intellij.flex.FlexTestUtils;
import com.intellij.javascript.flex.css.FlexStylesIndexableSetContributor;
import com.intellij.javascript.flex.mxml.schema.FlexSchemaHandler;
import com.intellij.lang.javascript.dialects.JSDialectSpecificHandlersFactory;
import com.intellij.lang.javascript.flex.FlexModuleType;
import com.intellij.lang.javascript.psi.JSFunction;
import com.intellij.lang.javascript.psi.ecmal4.JSAttributeListOwner;
import com.intellij.lang.javascript.psi.ecmal4.JSClass;
import com.intellij.lang.javascript.psi.resolve.JSClassResolver;
import com.intellij.lang.javascript.psi.resolve.JSInheritanceUtil;
import com.intellij.lang.javascript.refactoring.JSVisibilityUtil;
import com.intellij.lang.javascript.refactoring.memberPullUp.JSPullUpConflictsUtil;
import com.intellij.lang.javascript.refactoring.memberPullUp.JSPullUpHelper;
import com.intellij.lang.javascript.refactoring.util.JSInterfaceContainmentVerifier;
import com.intellij.lang.javascript.refactoring.util.JSMemberInfo;
import com.intellij.openapi.command.WriteCommandAction;
import com.intellij.openapi.fileEditor.FileDocumentManager;
import com.intellij.openapi.module.Module;
import com.intellij.openapi.module.ModuleType;
import com.intellij.openapi.util.Conditions;
import com.intellij.openapi.vfs.LocalFileSystem;
import com.intellij.openapi.vfs.VirtualFile;
import com.intellij.openapi.vfs.newvfs.impl.VfsRootAccess;
import com.intellij.psi.PsiDocumentManager;
import com.intellij.psi.PsiElement;
import com.intellij.psi.impl.source.PostprocessReformattingAspect;
import com.intellij.psi.search.GlobalSearchScope;
import com.intellij.refactoring.MultiFileTestCase;
import com.intellij.refactoring.classMembers.MemberInfoBase;
import com.intellij.refactoring.util.DocCommentPolicy;
import com.intellij.testFramework.PsiTestUtil;
import com.intellij.util.ArrayUtil;
import com.intellij.util.containers.MultiMap;
import org.jetbrains.annotations.NotNull;

import java.io.File;
import java.util.ArrayList;
import java.util.List;

import static com.intellij.openapi.vfs.VfsUtilCore.convertFromUrl;
import static com.intellij.openapi.vfs.VfsUtilCore.urlToPath;

public class FlexPullUpTest extends MultiFileTestCase {
  @Override
  protected void setUp() throws Exception {
    VfsRootAccess.allowRootAccess(getTestRootDisposable(),
                                  urlToPath(convertFromUrl(FlexSchemaHandler.class.getResource("z.xsd"))),
                                  urlToPath(convertFromUrl(FlexStylesIndexableSetContributor.class.getResource("FlexStyles.as"))));
    super.setUp();
  }

  @NotNull
  @Override
  protected String getTestRoot() {
    return "pullUp/";
  }

  @Override
  protected String getTestDataPath() {
    return FlexTestUtils.getTestDataPath("");
  }

  @Override
  protected ModuleType getModuleType() {
    return FlexModuleType.getInstance();
  }

  @Override
  protected void setUpJdk() {
    FlexTestUtils.setupFlexSdk(myModule, getTestName(false), getClass());
  }

  private void doTestPullUp(final String from, final String to, final int docCommentPolicy, final String... toPullUp) throws Exception {
    doTest(new PerformAction() {
      @Override
      public void performAction(VirtualFile rootDir, VirtualFile rootAfter) throws Exception {
        FlexPullUpTest.this.performAction(from, to, docCommentPolicy, ArrayUtil.EMPTY_STRING_ARRAY, toPullUp);
      }
    }, false);
  }

  private void doTestConflicts(final String from, final String to, final int docCommentPolicy, String[] conflicts, final String... toPullUp)
    throws Exception {
    String filePath = getTestDataPath() + getTestRoot() + getTestName(false);
    if (new File(filePath + ".as").exists()) {
      configureByFile(getTestRoot() + getTestName(false) + ".as");
    }
    else {
      String rootBefore = filePath + "/before";
      VirtualFile rootDir = PsiTestUtil.createTestProjectStructure(myProject, myModule, rootBefore, myFilesToDelete, false);
      prepareProject(rootDir);
      PsiDocumentManager.getInstance(myProject).commitAllDocuments();
    }
    performAction(from, to, docCommentPolicy, conflicts, toPullUp);
  }

  private void performAction(String from, String to, final int docCommentPolicy, String[] expectedConflicts, final String[] toPullUp) {
    final JSClassResolver resolver = JSDialectSpecificHandlersFactory.forLanguage(JavaScriptSupportLoader.ECMA_SCRIPT_L4).getClassResolver();
    final JSClass sourceClass = (JSClass)resolver.findClassByQName(from, GlobalSearchScope.projectScope(getProject()));
    assertNotNull("source class not found: " + sourceClass, sourceClass);

    final JSClass targetClass = (JSClass)resolver.findClassByQName(to, GlobalSearchScope.projectScope(getProject()));
    assertNotNull("target class not found: " + targetClass, targetClass);

    assertTrue("Source should be a subclass of target", JSInheritanceUtil.isParentClass(sourceClass, targetClass));

    final List<JSMemberInfo> memberInfos = getMemberInfos(toPullUp, sourceClass, false);

    final JSMemberInfo[] infosArray = JSMemberInfo.getSelected(memberInfos, sourceClass, Conditions.<JSMemberInfo>alwaysTrue());
    MultiMap<PsiElement, String> conflicts =
      JSPullUpConflictsUtil.checkConflicts(infosArray, sourceClass, targetClass, new JSInterfaceContainmentVerifier() {
        @Override
        public boolean checkedInterfacesContain(JSFunction psiMethod) {
          return JSPullUpHelper.checkedInterfacesContain(memberInfos, psiMethod);
        }
      }, JSVisibilityUtil.DEFAULT_OPTIONS);

    ArrayList<String> messages = new ArrayList<>(conflicts.values());
    for (int i = 0; i < messages.size(); i++) {
      messages.set(i, messages.get(i).replaceAll("<[^>]+>", ""));
    }
    assertSameElements(messages, expectedConflicts);
    if (conflicts.isEmpty()) {
      WriteCommandAction.runWriteCommandAction(null, () -> {
        new JSPullUpHelper(sourceClass, targetClass, infosArray, docCommentPolicy).moveMembersToBase();
        myProject.getComponent(PostprocessReformattingAspect.class).doPostponedFormatting();
      });

      FileDocumentManager.getInstance().saveAllDocuments();
    }
  }

  public static List<JSMemberInfo> getMemberInfos(final String[] members, JSClass clazz, boolean makeAbstract) {
    final List<JSMemberInfo> memberInfos = new ArrayList<>();
    JSMemberInfo.extractClassMembers(clazz, memberInfos, new MemberInfoBase.Filter<JSAttributeListOwner>() {
      @Override
      public boolean includeMember(JSAttributeListOwner member) {
        return ArrayUtil.contains(member.getName(), members);
      }
    });
    //assertTrue("Nothing to process", !memberInfos.isEmpty());
    for (JSMemberInfo memberInfo : memberInfos) {
      memberInfo.setChecked(true);
      if (makeAbstract) {
        memberInfo.setToAbstract(true);
      }
    }
    JSMemberInfo.sortByOffset(memberInfos);
    return memberInfos;
  }

  public void testMethod() throws Exception {
    doTestPullUp("foo.Sub", "bar.Base", DocCommentPolicy.ASIS, "zz", "bar", "st2", "st3");
  }

  public void testImplements1() throws Exception {
    doTestPullUp("Sub", "Base", DocCommentPolicy.ASIS, "IFoo");
  }

  public void testImplements2() throws Exception {
    doTestPullUp("Sub", "Base", DocCommentPolicy.ASIS, "IFoo");
  }

  public void testImplements3() throws Exception {
    doTestPullUp("Sub", "Base", DocCommentPolicy.ASIS, "IFoo");
  }

  public void testImplements4() throws Exception {
    doTestPullUp("Sub", "Base", DocCommentPolicy.ASIS, "abc", "IFoo");
  }

  public void testImplements5() throws Exception {
    doTestPullUp("Sub", "Base", DocCommentPolicy.ASIS, "abc", "IFoo");
  }

  public void testAbstractize() throws Exception {
    doTestPullUp("Sub", "IFoo", DocCommentPolicy.ASIS, "foo");
  }

  public void testAbstractize2() throws Exception {
    doTestPullUp("Sub", "IFoo", DocCommentPolicy.COPY, "foo");
  }

  public void testAbstractize3() throws Exception {
    doTestPullUp("Sub", "IFoo", DocCommentPolicy.MOVE, "foo");
  }

  public void testInterfaceMethod() throws Exception {
    doTestPullUp("ISub", "IBase", DocCommentPolicy.ASIS, "foo");
  }

  public void testProperty() throws Exception {
    doTestPullUp("Sub", "Super", DocCommentPolicy.ASIS, "prop", "func");
  }

  public void testField() throws Exception {
    doTestPullUp("bar.Sub", "foo.Super", DocCommentPolicy.ASIS, "p", "d", "e");
  }

  public void testStaticField() throws Exception {
    doTestPullUp("Sub", "Super", DocCommentPolicy.ASIS, "foo", "bar", "zzz");
  }

  public void testConflicts1() throws Exception {
    String[] conflicts = new String[]{"Class Super already contains a method foo(Aux)",
      "Method foo(Aux) uses field Sub.v, which is not moved to the superclass",
      "Method foo(Aux) uses field Sub.v2, which is not moved to the superclass",
      "Method foo(Aux) uses method Sub.bar(), which is not moved to the superclass",
      "Class Aux with internal visibility won't be accessible from method foo(Aux)",
      "Inner class FileLocal won't be accessible from method foo(Aux)",
      "Method foo(Aux) uses field Sub.st1, which is not accessible from the superclass",
      "Method foo(Aux) uses field Sub.st2, which is not accessible from the superclass",
      "Field fooVar uses field Sub.st4, which is not accessible from the superclass",
      "Class Aux2 with internal visibility won't be accessible from field fooVar2"};
    doTestConflicts("bar.Sub", "foo.Super", DocCommentPolicy.ASIS, conflicts, "foo", "fooVar", "fooVar2");
  }

  public void testModuleConflicts() throws Exception {
    final Module module2 = doCreateRealModule("module2");
    final VirtualFile contentRoot =
      LocalFileSystem.getInstance().findFileByPath(getTestDataPath() + getTestRoot() + getTestName(false) + "/module2");
    PsiTestUtil.addSourceRoot(module2, contentRoot);
    FlexTestUtils.addFlexModuleDependency(myModule, module2);

    String[] conflicts = new String[]{"Class A, referenced in method Sub.foo(A), will not be accessible in module module2"};
    doTestConflicts("Sub", "ISuper", DocCommentPolicy.ASIS, conflicts, "foo");
  }

  public void testEscalate() throws Exception {
    doTestPullUp("bar.Sub", "foo.Super", DocCommentPolicy.ASIS, "priv", "prot", "intern", "pub", "priv2", "prot2", "intern2", "pub2", "bar",
                 "zzz");
  }

  public void testSuperMethodCall() throws Exception {
    doTestPullUp("Sub", "Super", DocCommentPolicy.ASIS, "foo", "abc");
  }

  public void testExistingMethodInSuperInterface() throws Exception {
    String[] conflict = new String[]{"Interface ISuper already contains a method foo()"};
    doTestConflicts("Sub", "ISuper", DocCommentPolicy.ASIS, conflict, "foo");
  }

  public void testInaccessibleInterface() throws Exception {
    String[] conflict = new String[]{"Interface foo.IFoo with internal visibility won't be accessible from class bar.Super"};
    doTestConflicts("foo.Sub", "bar.Super", DocCommentPolicy.ASIS, conflict, "IFoo");
  }

  public void testImplements6() throws Exception {
    doTestPullUp("foo.Sub", "bar.Super", DocCommentPolicy.ASIS, "InterfFoo", "InterfRoot");
  }

  public void testNamespaces1() throws Exception {
    doTestPullUp("bar.Sub", "foo.Super", DocCommentPolicy.ASIS, "foo", "bar");
  }

  @JSTestOptions({JSTestOption.WithFlexFacet, JSTestOption.WithGumboSdk})
  public void testVector() throws Exception {
    doTestPullUp("foo.From", "To", DocCommentPolicy.ASIS, "foo");
  }

  public void testOverrideToInterface() throws Exception {
    doTestPullUp("From", "ITo", DocCommentPolicy.ASIS, "foo");
  }

  public void testNoQualifyToInterface() throws Exception {
    doTestPullUp("From", "ITo", DocCommentPolicy.ASIS, "abc");
  }

  public void testNoQualifyToInterface2() throws Exception {
    doTestPullUp("From", "ITo", DocCommentPolicy.ASIS, "abc");
  }

  public void testAmbiguousImplements() throws Exception {
    doTestPullUp("Sub", "Base", DocCommentPolicy.ASIS, "ff", "SomeType");
  }

  public void testAbstractizeConflicts() throws Exception {
    String[] conflicts = new String[]{
      "Class Aux1 with internal visibility won't be accessible from method foo(Aux1, Aux2)",
      "Class Aux1 with internal visibility won't be accessible from method foo2(Aux1, Aux2)",
      "Class Aux1 with internal visibility won't be accessible from method foo4(Aux1)",
      "Class Aux2 with internal visibility won't be accessible from method foo(Aux1, Aux2)",
      "Class Aux2 with internal visibility won't be accessible from method foo2(Aux1, Aux2)",
      "Class Aux3 with internal visibility won't be accessible from method foo(Aux1, Aux2)"};
    doTestConflicts("bar.Sub", "foo.ISuper", DocCommentPolicy.ASIS, conflicts, "foo", "foo2", "foo3", "foo4", "foo5");
  }

  public void testOrdering1() throws Exception {
    doTestPullUp("From", "To", DocCommentPolicy.ASIS, "foo", "v");
  }

  public void testOrdering2() throws Exception {
    doTestPullUp("From", "To", DocCommentPolicy.ASIS, "foo", "v", "v2");
  }

  public void testUsages1() throws Exception {
    doTestPullUp("Sub", "Super", DocCommentPolicy.ASIS, "foo", "foo2", "foo3", "foo4");
  }

}
