package software.coley.recaf.services.cell.builtin;

import jakarta.annotation.Nonnull;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import javafx.collections.ObservableList;
import javafx.scene.control.ContextMenu;
import javafx.scene.control.MenuItem;
import org.slf4j.Logger;
import software.coley.recaf.analytics.logging.Logging;
import software.coley.recaf.info.ClassInfo;
import software.coley.recaf.info.JvmClassInfo;
import software.coley.recaf.info.member.FieldMember;
import software.coley.recaf.path.ClassPathNode;
import software.coley.recaf.path.IncompletePathException;
import software.coley.recaf.path.PathNodes;
import software.coley.recaf.services.cell.*;
import software.coley.recaf.services.navigation.Actions;
import software.coley.recaf.ui.contextmenu.ContextMenuBuilder;
import software.coley.recaf.util.ClipboardUtil;
import software.coley.recaf.util.Unchecked;
import software.coley.recaf.workspace.model.Workspace;
import software.coley.recaf.workspace.model.bundle.ClassBundle;
import software.coley.recaf.workspace.model.bundle.JvmClassBundle;
import software.coley.recaf.workspace.model.resource.WorkspaceResource;

import java.util.List;

import static org.kordamp.ikonli.carbonicons.CarbonIcons.*;

/**
 * Basic implementation for {@link FieldContextMenuProviderFactory}.
 *
 * @author Matt Coley
 */
@ApplicationScoped
public class BasicFieldContextMenuProviderFactory extends AbstractContextMenuProviderFactory
		implements FieldContextMenuProviderFactory {
	private static final Logger logger = Logging.get(BasicFieldContextMenuProviderFactory.class);

	@Inject
	public BasicFieldContextMenuProviderFactory(@Nonnull TextProviderService textService,
												@Nonnull IconProviderService iconService,
												@Nonnull Actions actions) {
		super(textService, iconService, actions);
	}

	@Nonnull
	@Override
	public ContextMenuProvider getFieldContextMenuProvider(@Nonnull ContextSource source,
														   @Nonnull Workspace workspace,
														   @Nonnull WorkspaceResource resource,
														   @Nonnull ClassBundle<? extends ClassInfo> bundle,
														   @Nonnull ClassInfo declaringClass,
														   @Nonnull FieldMember field) {
		return () -> {
			TextProvider nameProvider = textService.getFieldMemberTextProvider(workspace, resource, bundle, declaringClass, field);
			IconProvider iconProvider = iconService.getClassMemberIconProvider(workspace, resource, bundle, declaringClass, field);
			ContextMenu menu = new ContextMenu();
			addHeader(menu, nameProvider.makeText(), iconProvider.makeIcon());
			var builder = new ContextMenuBuilder(menu, source).forMember(workspace, resource, bundle, declaringClass, field);

			if (source.isReference()) {
				builder.item("menu.goto.field", ARROW_RIGHT, () -> {
					ClassPathNode classPath = PathNodes.classPath(workspace, resource, bundle, declaringClass);
					try {
						actions.gotoDeclaration(classPath)
								.requestFocus(field);
					} catch (IncompletePathException ex) {
						logger.error("Cannot go to field due to incomplete path", ex);
					}
				});
			} else {
				builder.item("menu.tab.copypath", COPY_LINK, () -> ClipboardUtil.copyString(declaringClass, field));
				builder.item("menu.edit.assemble.field", EDIT, () -> Unchecked.runnable(() ->
						actions.openAssembler(PathNodes.memberPath(workspace, resource, bundle, declaringClass, field))
				).run());

				if (declaringClass.isJvmClass()) {
					JvmClassBundle jvmBundle = (JvmClassBundle) bundle;
					JvmClassInfo declaringJvmClass = declaringClass.asJvmClass();

					builder.item("menu.edit.copy", COPY_FILE, () -> actions.copyClass(workspace, resource, jvmBundle, declaringJvmClass));
					builder.item("menu.edit.delete", TRASH_CAN, () -> actions.deleteClassFields(workspace, resource, jvmBundle, declaringJvmClass, List.of(field)));
				}

				// TODO: implement operations
				//  - Edit
				//    - Add annotation
				//    - Remove annotations
			}
			// TODO: Implement search UI, and open that when these actions are run
			// Search actions
			builder.item("menu.search.field-references", CODE, () -> {}).disableWhen(true);

			// Refactor actions
			builder.memberItem("menu.refactor.rename", TAG_EDIT, actions::renameField);

			return menu;
		};
	}
}
