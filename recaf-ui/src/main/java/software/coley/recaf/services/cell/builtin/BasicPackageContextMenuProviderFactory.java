package software.coley.recaf.services.cell.builtin;

import jakarta.annotation.Nonnull;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import javafx.collections.ObservableList;
import javafx.scene.control.ContextMenu;
import javafx.scene.control.Menu;
import javafx.scene.control.MenuItem;
import org.kordamp.ikonli.carbonicons.CarbonIcons;
import software.coley.recaf.info.ClassInfo;
import software.coley.recaf.services.cell.*;
import software.coley.recaf.services.navigation.Actions;
import software.coley.recaf.ui.contextmenu.ContextMenuBuilder;
import software.coley.recaf.ui.contextmenu.DirectoryMenuBuilder;
import software.coley.recaf.util.ClipboardUtil;
import software.coley.recaf.util.Lang;
import software.coley.recaf.workspace.model.Workspace;
import software.coley.recaf.workspace.model.bundle.ClassBundle;
import software.coley.recaf.workspace.model.bundle.JvmClassBundle;
import software.coley.recaf.workspace.model.resource.WorkspaceResource;

import static org.kordamp.ikonli.carbonicons.CarbonIcons.*;
import static org.kordamp.ikonli.carbonicons.CarbonIcons.TAG_EDIT;
import static software.coley.recaf.util.Menus.action;

/**
 * Basic implementation for {@link PackageContextMenuProviderFactory}.
 *
 * @author Matt Coley
 */
@ApplicationScoped
public class BasicPackageContextMenuProviderFactory extends AbstractContextMenuProviderFactory
		implements PackageContextMenuProviderFactory {
	@Inject
	public BasicPackageContextMenuProviderFactory(@Nonnull TextProviderService textService,
												  @Nonnull IconProviderService iconService,
												  @Nonnull Actions actions) {
		super(textService, iconService, actions);
	}

	@Nonnull
	@Override
	public ContextMenuProvider getPackageContextMenuProvider(@Nonnull ContextSource source,
															 @Nonnull Workspace workspace,
															 @Nonnull WorkspaceResource resource,
															 @Nonnull ClassBundle<? extends ClassInfo> bundle,
															 @Nonnull String packageName) {
		return () -> {
			TextProvider nameProvider = textService.getPackageTextProvider(workspace, resource, bundle, packageName);
			IconProvider iconProvider = iconService.getPackageIconProvider(workspace, resource, bundle, packageName);
			ContextMenu menu = new ContextMenu();
			addHeader(menu, nameProvider.makeText(), iconProvider.makeIcon());
			var builder = new ContextMenuBuilder(menu, source).forDirectory(workspace, resource, bundle, packageName);

			if (source.isDeclaration()) {
				builder.item("menu.tab.copypath", COPY_LINK, () -> ClipboardUtil.copyString(packageName));
				if (bundle instanceof JvmClassBundle) {
					var jvmBuilder = builder.cast(JvmClassBundle.class);
					jvmBuilder.directoryItem("menu.edit.copy", COPY_FILE, actions::copyPackage);
					jvmBuilder.directoryItem("menu.edit.delete", TRASH_CAN, actions::deletePackage);

					var refactor = jvmBuilder.submenu("menu.refactor", PAINT_BRUSH);
					refactor.directoryItem("menu.refactor.move", STACKED_MOVE, actions::movePackage);
					refactor.directoryItem("menu.refactor.rename", TAG_EDIT, actions::renamePackage);
				}
				// TODO: implement operations
				//  - Search references
			}

			return menu;
		};
	}
}
