package software.coley.recaf.services.assembler;

import jakarta.annotation.Nonnull;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import software.coley.recaf.info.JvmClassInfo;
import software.coley.recaf.services.compile.CompilerDiagnostic;
import software.coley.recaf.test.TestBase;
import software.coley.recaf.test.TestClassUtils;
import software.coley.recaf.test.dummy.ClassWithFieldsAndMethods;
import software.coley.recaf.test.dummy.ClassWithRequiredConstructor;
import software.coley.recaf.test.dummy.DummyEnum;
import software.coley.recaf.workspace.model.Workspace;

import java.io.IOException;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for {@link ExpressionCompiler}
 */
class ExpressionCompilerTest extends TestBase {
	static ExpressionCompiler assembler;
	static Workspace workspace;
	static JvmClassInfo targetClass;
	static JvmClassInfo targetCtorClass;
	static JvmClassInfo targetEnum;

	@BeforeAll
	static void setup() throws IOException {
		assembler = recaf.get(ExpressionCompiler.class);
		targetClass = TestClassUtils.fromRuntimeClass(ClassWithFieldsAndMethods.class);
		targetCtorClass = TestClassUtils.fromRuntimeClass(ClassWithRequiredConstructor.class);
		targetEnum = TestClassUtils.fromRuntimeClass(DummyEnum.class);
		workspace = TestClassUtils.fromBundle(TestClassUtils.fromClasses(targetClass, targetCtorClass, targetEnum));
		workspaceManager.setCurrent(workspace);
	}

	@AfterEach
	void cleanup() {
		assembler.clearContext();
	}

	@Test
	void importSupport() {
		ExpressionResult result = compile("""
				import java.util.Random;
				    
				try {
					Random random = new Random();
				 	int a = random.nextInt(100);
				 	int b = random.nextInt(100);
				 	System.out.println(a + " / " + b + " = " + (a/b));
				} catch (Exception ex) {
					System.out.println("Fail: " + ex);
				}
				""");
		assertSuccess(result);
	}

	@Test
	void classContext() {
		assembler.setClassContext(targetClass);
		ExpressionResult result = compile("""
				int localConst = CONST_INT;
				int localField = finalInt;
				int localMethod = plusTwo();
				int add = localConst + localField + localMethod;
				""");
		assertSuccess(result);
	}

	@Test
	void classContextWithRequiredCtor() {
		assembler.setClassContext(targetCtorClass);
		ExpressionResult result = compile("");
		assertSuccess(result);
	}

	@Test
	void enumContext() {
		assembler.setClassContext(targetEnum);
		ExpressionResult result = compile("""
				int i1 = ONE.ordinal();
				int i2 = TWO.ordinal();
				int i3 = THREE.ordinal();
				int add = i1 + i2 + i3;
				""");
		assertSuccess(result);
	}

	@Test
	void classAndMethodContextForParameters() {
		assembler.setClassContext(targetClass);
		assembler.setMethodContext(targetClass.getFirstDeclaredMethodByName("methodWithParameters"));
		ExpressionResult result = compile("""
				System.out.println(foo + ": " +
						Long.toHexString(wide) +
						"/" +
						Float.floatToIntBits(decimal) +
						" s=" + strings.get(0));
				""");
		assertSuccess(result);
	}

	@Test
	void classAndMethodContextForLocals() {
		// Tests that local variables are accessible to the expression compiler
		assembler.setClassContext(targetClass);
		assembler.setMethodContext(targetClass.getFirstDeclaredMethodByName("methodWithLocalVariables"));
		ExpressionResult result = compile("""
				out.println(message.contains("0") ? "Has zero" : "No zero found");
				""");
		assertSuccess(result);
	}

	@Test
	void classAndMethodContextForConstructor() {
		// Tests that the assembler works for constructor method contexts
		assembler.setClassContext(targetClass);
		assembler.setMethodContext(targetClass.getFirstDeclaredMethodByName("<init>"));
		ExpressionResult result = compile("");
		assertSuccess(result);
	}

	@Test
	void classAndMethodContextForStaticInitializer() {
		// Tests that the assembler works for static initializer method contexts
		assembler.setClassContext(targetEnum);
		assembler.setMethodContext(targetEnum.getFirstDeclaredMethodByName("<clinit>"));
		ExpressionResult result = compile("");
		assertSuccess(result);
	}

	private static void assertSuccess(@Nonnull ExpressionResult result) {
		assertNull(result.getException(), "Exception thrown when compiling: " + result.getException());
		assertTrue(result.getDiagnostics().isEmpty(), "There were " + result.getDiagnostics().size() + " compiler messages");
		assertTrue(result.wasSuccess(), "Missing assembler output");
	}

	@Nonnull
	private static ExpressionResult compile(@Nonnull String expressionResult) {
		ExpressionResult result = assembler.compile(expressionResult);
		List<CompilerDiagnostic> diagnostics = result.getDiagnostics();
		diagnostics.forEach(System.out::println);
		ExpressionCompileException exception = result.getException();
		if (exception != null)
			fail(exception);
		String assembly = result.getAssembly();
		if (assembly != null)
			System.out.println(assembly);
		return result;
	}
}