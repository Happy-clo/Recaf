package software.coley.recaf.ui.pane.editing.assembler;

import jakarta.annotation.Nonnull;
import jakarta.enterprise.context.Dependent;
import jakarta.enterprise.inject.Instance;
import jakarta.inject.Inject;
import me.darknet.assembler.ast.ASTElement;
import me.darknet.assembler.ast.specific.ASTClass;
import me.darknet.assembler.ast.specific.ASTField;
import me.darknet.assembler.ast.specific.ASTMethod;
import me.darknet.assembler.compile.JavaClassRepresentation;
import me.darknet.assembler.compiler.ClassRepresentation;
import me.darknet.assembler.compiler.ClassResult;
import me.darknet.assembler.error.Error;
import me.darknet.assembler.error.Result;
import me.darknet.assembler.parser.Token;
import me.darknet.assembler.util.Location;
import org.fxmisc.richtext.CodeArea;
import org.slf4j.Logger;
import software.coley.recaf.analytics.logging.Logging;
import software.coley.recaf.info.ClassInfo;
import software.coley.recaf.info.member.ClassMember;
import software.coley.recaf.info.member.MethodMember;
import software.coley.recaf.path.ClassMemberPathNode;
import software.coley.recaf.path.ClassPathNode;
import software.coley.recaf.path.PathNode;
import software.coley.recaf.services.assembler.AssemblerPipeline;
import software.coley.recaf.services.assembler.AssemblerPipelineManager;
import software.coley.recaf.services.navigation.ClassNavigable;
import software.coley.recaf.services.navigation.UpdatableNavigable;
import software.coley.recaf.ui.LanguageStylesheets;
import software.coley.recaf.ui.config.KeybindingConfig;
import software.coley.recaf.ui.control.BoundTab;
import software.coley.recaf.ui.control.IconView;
import software.coley.recaf.ui.control.richtext.Editor;
import software.coley.recaf.ui.control.richtext.bracket.BracketMatchGraphicFactory;
import software.coley.recaf.ui.control.richtext.bracket.SelectedBracketTracking;
import software.coley.recaf.ui.control.richtext.problem.*;
import software.coley.recaf.ui.control.richtext.search.SearchBar;
import software.coley.recaf.ui.control.richtext.syntax.RegexLanguages;
import software.coley.recaf.ui.control.richtext.syntax.RegexSyntaxHighlighter;
import software.coley.recaf.ui.pane.editing.AbstractContentPane;
import software.coley.recaf.ui.pane.editing.tabs.FieldsAndMethodsPane;
import software.coley.recaf.util.*;
import software.coley.recaf.workspace.model.bundle.Bundle;

import java.time.Duration;
import java.util.Collection;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;

/**
 * Display dissassembled {@link ClassInfo} and {@link ClassMember} content.
 *
 * @author Matt Coley
 */
@Dependent
public class AssemblerPane extends AbstractContentPane<PathNode<?>> implements UpdatableNavigable, ClassNavigable {
	private static final Logger logger = Logging.get(AssemblerPane.class);

	private final AssemblerPipelineManager pipelineManager;
	private final AssemblerToolTabs assemblerToolTabs;
	private final ProblemTracking problemTracking = new ProblemTracking();
	private final Editor editor = new Editor();
	private final AtomicBoolean updateLock = new AtomicBoolean();
	private final Instance<FieldsAndMethodsPane> fieldsAndMethodsPaneProvider;
	private AssemblerPipeline<? extends ClassInfo, ? extends ClassResult, ? extends ClassRepresentation> pipeline;
	private ClassResult lastResult;
	private ClassRepresentation lastAssembledClassRepresentation;
	private ClassInfo lastAssembledClass;
	private List<Token> lastTokens;
	private List<ASTElement> lastRoughAst;
	private List<ASTElement> lastPartialAst;
	private List<ASTElement> lastConcreteAst;

	@Inject
	public AssemblerPane(@Nonnull AssemblerPipelineManager pipelineManager,
						 @Nonnull AssemblerToolTabs assemblerToolTabs,
						 @Nonnull AssemblerContextActionSupport contextActionSupport,
						 @Nonnull SearchBar searchBar,
						 @Nonnull KeybindingConfig keys,
						 @Nonnull Instance<FieldsAndMethodsPane> fieldsAndMethodsPaneProvider) {
		this.pipelineManager = pipelineManager;
		this.assemblerToolTabs = assemblerToolTabs;
		this.fieldsAndMethodsPaneProvider = fieldsAndMethodsPaneProvider;

		int timeToWait = pipelineManager.getServiceConfig().getDisassemblyAstParseDelay().getValue();

		editor.getCodeArea().getStylesheets().add(LanguageStylesheets.getJasmStylesheet());
		editor.setSelectedBracketTracking(new SelectedBracketTracking());
		editor.setSyntaxHighlighter(new RegexSyntaxHighlighter(RegexLanguages.getJasmLanguage()));
		editor.setProblemTracking(problemTracking);
		editor.getRootLineGraphicFactory().addLineGraphicFactories(
				new BracketMatchGraphicFactory(),
				new ProblemGraphicFactory()
		);
		editor.getTextChangeEventStream()
				.successionEnds(Duration.ofMillis(timeToWait))
				.addObserver(e -> assemble());

		contextActionSupport.install(editor);
		searchBar.install(editor);

		// Context action should be passed along path updates
		children.add(contextActionSupport);
		children.add(assemblerToolTabs);

		setOnKeyPressed(event -> {
			if (keys.getSave().match(event))
				assembleAndUpdateWorkspace();
		});
	}

	/**
	 * Called by {@link #lateInitForClass(ClassPathNode)} or {@link #lateInitForMethod(ClassMemberPathNode)}.
	 * <p/>
	 * Does late initialization that couldn't be done in the constructor.
	 */
	private void lateInit() {
		// Some tool tabs are not initialized immediately in the constructor, so we do a late installation of them.
		assemblerToolTabs.install(editor);
	}

	/**
	 * Called by {@link #onUpdatePath(PathNode)} once before the {@link #path} is set for the first time.
	 * <p/>
	 * Sets up {@link FieldsAndMethodsPane} as a side-tab and sets up notifications for {@link AssemblerToolTabs}
	 * and its children when the selected {@link ClassMember} in the {@link #lastConcreteAst} changes.
	 *
	 * @param classPathNode
	 * 		The given path.
	 */
	private void lateInitForClass(@Nonnull ClassPathNode classPathNode) {
		// Show declared fields/methods
		FieldsAndMethodsPane fieldsAndMethodsPane = fieldsAndMethodsPaneProvider.get();
		fieldsAndMethodsPane.setupSelectionNavigationListener(this);
		addSideTab(new BoundTab(Lang.getBinding("fieldsandmethods.title"),
				new IconView(Icons.getImage(Icons.FIELD_N_METHOD)),
				fieldsAndMethodsPane
		));
		fieldsAndMethodsPane.onUpdatePath(classPathNode);

		// Since the content displayed is for a whole class, and the tool tabs are scoped to a method, we need to
		// update them when a method is selected. We do so by tracking the caret position for being within the
		// range of one of the methods in the last AST model.
		editor.getCaretPosEventStream().addObserver(e -> {
			if (lastConcreteAst == null)
				return;
			ClassInfo declaringClass = classPathNode.getValue();
			int caret = editor.getCodeArea().getCaretPosition();
			for (ASTElement root : lastConcreteAst) {
				if (root instanceof ASTClass astClass) {
					for (ASTElement child : astClass.children()) {
						ClassMember classMember;
						if (child instanceof ASTMethod astMethod && astMethod.range().within(caret)) {
							String name = astMethod.getName().literal();
							String desc = astMethod.getDescriptor().literal();
							classMember = declaringClass.getDeclaredMethod(name, desc);
						} else if (child instanceof ASTField astField && astField.range().within(caret)) {
							String name = astField.getName().literal();
							String desc = astField.getDescriptor().literal();
							classMember = declaringClass.getDeclaredField(name, desc);
						} else {
							continue;
						}

						if (classMember != null) {
							ClassMemberPathNode memberPath = classPathNode.child(classMember);
							eachChild(UpdatableNavigable.class, c -> c.onUpdatePath(memberPath));
						} else {
							eachChild(UpdatableNavigable.class, c -> c.onUpdatePath(classPathNode));
						}
						eachChild(AssemblerBuildConsumer.class, c -> c.consumeClass(lastResult, lastAssembledClass));
						return;
					}
				}
			}
		});

		// Common init
		assemblerToolTabs.onUpdatePath(classPathNode);
		lateInit();
	}

	/**
	 * Called by {@link #onUpdatePath(PathNode)} once before the {@link #path} is set for the first time.
	 *
	 * @param memberPathNode
	 * 		The given path.
	 */
	private void lateInitForMethod(@Nonnull ClassMemberPathNode memberPathNode) {
		// Common init
		assemblerToolTabs.onUpdatePath(memberPathNode);
		lateInit();
	}

	@Override
	protected void generateDisplay() {
		if (!hasDisplay()) {
			setDisplay(editor);
			setBottom(assemblerToolTabs.getTabs());

			// Trigger a disassembly so the initial text is set in the editor.
			disassemble().whenComplete((unused, error) -> {
				editor.getCodeArea().getUndoManager().forgetHistory();
				if (error == null && unused.isOk())
					assemble();
			});
		}
	}

	@Nonnull
	@Override
	public PathNode<?> getPath() {
		return path;
	}

	@Nonnull
	@Override
	public ClassPathNode getClassPath() {
		return Objects.requireNonNull(path.getParentOfType(ClassInfo.class), "Missing class parent path");
	}

	@Override
	public void requestFocus(@Nonnull ClassMember member) {
		if (lastConcreteAst != null) {
			for (ASTElement root : lastConcreteAst) {
				if (root instanceof ASTClass astClass) {
					for (ASTElement child : astClass.children()) {
						String name;
						String desc;
						if (member.isMethod() && child instanceof ASTMethod astMethod) {
							name = astMethod.getName().literal();
							desc = astMethod.getDescriptor().literal();
						} else if (member.isField() && child instanceof ASTField astField) {
							name = astField.getName().literal();
							desc = astField.getDescriptor().literal();
						} else {
							name = desc = null;
						}
						if (member.getName().equals(name) && member.getDescriptor().equals(desc)) {
							CodeArea area = editor.getCodeArea();
							area.moveTo(child.range().start());
							area.showParagraphAtCenter(area.getCurrentParagraph());
							return;
						}
					}
				}
			}
		}
	}

	@Override
	public void onUpdatePath(@Nonnull PathNode<?> path) {
		// If we've not seen a path before, do late initialization for UI elements
		// that require path information.
		if (this.path == null)
			if (path instanceof ClassPathNode classPathNode)
				lateInitForClass(classPathNode);
			else if (path instanceof ClassMemberPathNode memberPathNode)
				lateInitForMethod(memberPathNode);

		// Update the path and call any path listeners.
		this.path = path;
		pathUpdateListeners.forEach(listener -> listener.accept(path));

		// Update UI state.
		if (!updateLock.get()) {
			pipeline = pipelineManager.getPipeline(path);

			// Setup from existing class data from the path.
			lastAssembledClass = path.getValueOfType(ClassInfo.class);
			lastAssembledClassRepresentation = pipeline.getRepresentation(Unchecked.cast(lastAssembledClass));
			lastResult = () -> lastAssembledClassRepresentation;
			eachChild(UpdatableNavigable.class, c -> c.onUpdatePath(path));
			eachChild(AssemblerBuildConsumer.class, c -> c.consumeClass(lastResult, lastAssembledClass));

			// Refresh the primary assembler display.
			FxThreadUtil.run(this::refreshDisplay);
		}
	}

	@Override
	public boolean isTrackable() {
		// Disabling tracking allows other panels with the same path-node to be opened.
		return false;
	}

	/**
	 * Disassemble the content of the {@link #path} and set the editor text to the resulting output.
	 *
	 * @return Future of disassembling completion.
	 */
	@Nonnull
	private CompletableFuture<Result<String>> disassemble() {
		problemTracking.removeByPhase(ProblemPhase.LINT);
		return CompletableFuture.supplyAsync(() -> pipeline.disassemble(path))
				.orTimeout(10, TimeUnit.SECONDS)
				.whenCompleteAsync((result, error) -> {
					if (result != null)
						acceptResult(result, editor::setText, ProblemPhase.LINT);
					else
						logger.error("Disassemble encountered an unexpected error", error);
				}, FxThreadUtil.executor());
	}

	/**
	 * Parse the current editor's text into AST.
	 *
	 * @return Future of parse completion.
	 */
	@Nonnull
	private CompletableFuture<Void> parseAST() {
		// Nothing to parse
		if (editor.getText().isBlank()) return CompletableFuture.completedFuture(null);

		// Clear lint errors since we are running the linter again.
		if (problemTracking.removeByPhase(ProblemPhase.LINT))
			FxThreadUtil.run(editor::redrawParagraphGraphics);

		return CompletableFuture.runAsync(() -> {
			try {
				// Tokenize the current input.
				Result<List<Token>> tokenResult = pipeline.tokenize(editor.getText(), "<assembler>");

				// Process any errors and assign the latest token list.
				if (tokenResult.hasErr())
					processErrors(tokenResult.errors(), ProblemPhase.LINT);
				lastTokens = tokenResult.get();

				// Attempt to parse the token list into 'rough' AST.
				acceptResult(pipeline.roughParse(lastTokens), roughAst -> {
					lastRoughAst = roughAst;

					// Attempt to complete parsing and transform the 'rough' AST into a 'concrete' AST.
					acceptResult(pipeline.concreteParse(roughAst), concreteAst -> {
						// The transform was a success.
						lastConcreteAst = concreteAst;
						eachChild(AssemblerAstConsumer.class, c -> c.consumeAst(concreteAst, AstPhase.CONCRETE));
					}, pAst -> {
						// The transform failed.
						lastPartialAst = pAst;
						eachChild(AssemblerAstConsumer.class, c -> c.consumeAst(pAst, AstPhase.CONCRETE_PARTIAL));
					}, ProblemPhase.LINT);
				}, pAst -> {
					// We failed to parse the token list fully, but may have a partial result.
					eachChild(AssemblerAstConsumer.class, c -> c.consumeAst(pAst, AstPhase.ROUGH_PARTIAL));
					lastPartialAst = pAst;
				}, ProblemPhase.LINT);
			} catch (Exception ex) {
				logger.error("Failed to parse assembler", ex);
			}
		});
	}

	/**
	 * Build the contents of the editor's text and update the workspace when successful.
	 *
	 * @return Future of parse completion.
	 */
	@Nonnull
	private CompletableFuture<Void> assemble() {
		// Ensure the AST is up-to-date before moving onto build stage.
		return parseAST().whenComplete((unused, error) -> {
			if (!problemTracking.getProblems().isEmpty() || lastConcreteAst == null)
				return;

			// Clear build errors since we are running the build process again.
			problemTracking.removeByPhase(ProblemPhase.BUILD);

			try {
				pipeline.assemble(lastConcreteAst, path).ifOk(result -> {
					ClassRepresentation representation = result.representation();

					lastResult = result;
					lastAssembledClassRepresentation = representation;

					if (representation instanceof JavaClassRepresentation javaClassRep) {
						lastAssembledClass = pipeline.getClassInfo(Unchecked.cast(javaClassRep));

						// Update the local path value, this will also inform sub-components of the new content.
						// The update-lock must be set so that we don't trigger a disassembly (which would trigger an endless loop)
						updateLock.set(true);
						if (path instanceof ClassPathNode classPath) {
							ClassPathNode newPath = classPath.getParent().child(lastAssembledClass);
							onUpdatePath(newPath);
						} else if (path instanceof ClassMemberPathNode memberPath) {
							ClassMember oldMember = memberPath.getValue();
							MethodMember newMember = lastAssembledClass.getDeclaredMethod(oldMember.getName(), oldMember.getDescriptor());
							ClassMemberPathNode newPath = memberPath.getParent().getParent().child(lastAssembledClass).child(newMember);
							onUpdatePath(newPath);
						}
						updateLock.set(false);
					}
					/*
					else if (representation instanceof AndroidClassRepresentation androidClassRep) {
						lastAssembledClass = pipeline.getClassInfo(Unchecked.cast(androidClassRep));
					}
					 */

					eachChild(AssemblerBuildConsumer.class, c -> c.consumeClass(result, lastAssembledClass));
				}).ifErr(errors -> processErrors(errors, ProblemPhase.BUILD));
			} catch (Throwable ex) {
				logger.error("Uncaught exception when assembling contents of {}", path, ex);
				FxThreadUtil.run(() -> Animations.animateFailure(editor, 1000));
			}
		});
	}

	@Nonnull
	@SuppressWarnings("unchecked")
	private CompletableFuture<Void> assembleAndUpdateWorkspace() {
		return assemble().whenComplete((unused, error) -> {
			if (lastAssembledClass != null && problemTracking.getProblemsByLevel(ProblemLevel.ERROR).isEmpty()) {
				updateLock.set(true);
				try {
					Bundle<ClassInfo> bundle = path.getValueOfType(Bundle.class);
					bundle.put(lastAssembledClass);
					FxThreadUtil.run(() -> Animations.animateSuccess(editor, 1000));
				} catch (Throwable t) {
					logger.error("Uncaught exception when updating class of {}", lastAssembledClass.getName(), t);
					FxThreadUtil.run(() -> Animations.animateWarn(editor, 1000));
				} finally {
					updateLock.set(false);
				}
			} else {
				FxThreadUtil.run(() -> Animations.animateFailure(editor, 1000));
			}
		});
	}

	/**
	 * Add the given errors to {@link #problemTracking} and refresh the UI.
	 *
	 * @param errors
	 * 		Problems to add.
	 * @param phase
	 * 		Phase the problems belong to.
	 */
	private void processErrors(@Nonnull Collection<Error> errors, @Nonnull ProblemPhase phase) {
		FxThreadUtil.run(() -> {
			for (Error error : errors) {
				Location location = error.getLocation();
				int line = location == null ? 1 : location.line();
				int column = location == null ? 1 : location.column();
				Problem problem = new Problem(line, column, ProblemLevel.ERROR, phase,
						error.getMessage());
				problemTracking.add(problem);

				// REMOVE IS TRACING PARSER ERRORS
				/*
				Throwable trace = new Throwable();
				trace.setStackTrace(error.getInCodeSource());
				logger.trace("Assembler error", trace);
				System.err.println(error);
				*/
			}

			if (!errors.isEmpty())
				editor.redrawParagraphGraphics();
		});
	}

	private <T> void acceptResult(Result<T> result, Consumer<T> acceptor, ProblemPhase phase) {
		result.ifOk(acceptor).ifErr(errors -> processErrors(errors, phase));
	}

	private <T> void acceptResult(Result<T> result, Consumer<T> acceptor, Consumer<T> pAcceptor, ProblemPhase phase) {
		result.ifOk(acceptor).ifErr((pOk, errors) -> {
			pAcceptor.accept(pOk);
			processErrors(errors, phase);
		});
	}
}
