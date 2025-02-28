package software.coley.recaf.services.navigation;

import jakarta.annotation.Nonnull;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.inject.Instance;
import jakarta.inject.Inject;
import javafx.collections.ObservableList;
import javafx.scene.Node;
import javafx.scene.Parent;
import javafx.scene.control.ContextMenu;
import javafx.scene.control.Menu;
import javafx.scene.control.MenuItem;
import javafx.scene.control.Tab;
import org.kordamp.ikonli.carbonicons.CarbonIcons;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassWriter;
import org.slf4j.Logger;
import software.coley.recaf.analytics.logging.Logging;
import software.coley.recaf.info.*;
import software.coley.recaf.info.annotation.AnnotationInfo;
import software.coley.recaf.info.builder.JvmClassInfoBuilder;
import software.coley.recaf.info.member.ClassMember;
import software.coley.recaf.info.member.FieldMember;
import software.coley.recaf.info.member.MethodMember;
import software.coley.recaf.path.*;
import software.coley.recaf.services.Service;
import software.coley.recaf.services.cell.IconProviderService;
import software.coley.recaf.services.cell.TextProviderService;
import software.coley.recaf.services.mapping.IntermediateMappings;
import software.coley.recaf.services.mapping.MappingApplier;
import software.coley.recaf.services.mapping.MappingResults;
import software.coley.recaf.ui.control.FontIconView;
import software.coley.recaf.ui.control.popup.ItemListSelectionPopup;
import software.coley.recaf.ui.control.popup.ItemTreeSelectionPopup;
import software.coley.recaf.ui.control.popup.NamePopup;
import software.coley.recaf.ui.docking.DockingManager;
import software.coley.recaf.ui.docking.DockingRegion;
import software.coley.recaf.ui.docking.DockingTab;
import software.coley.recaf.ui.pane.editing.android.AndroidClassPane;
import software.coley.recaf.ui.pane.editing.assembler.AssemblerPane;
import software.coley.recaf.ui.pane.editing.binary.BinaryXmlFilePane;
import software.coley.recaf.ui.pane.editing.jvm.JvmClassEditorType;
import software.coley.recaf.ui.pane.editing.jvm.JvmClassPane;
import software.coley.recaf.ui.pane.editing.media.AudioFilePane;
import software.coley.recaf.ui.pane.editing.media.ImageFilePane;
import software.coley.recaf.ui.pane.editing.media.VideoFilePane;
import software.coley.recaf.ui.pane.editing.text.TextFilePane;
import software.coley.recaf.util.*;
import software.coley.recaf.util.visitors.*;
import software.coley.recaf.workspace.model.Workspace;
import software.coley.recaf.workspace.model.bundle.*;
import software.coley.recaf.workspace.model.resource.WorkspaceResource;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Objects;
import java.util.function.Consumer;
import java.util.function.Supplier;
import java.util.stream.Collectors;

import static software.coley.recaf.util.Menus.*;
import static software.coley.recaf.util.StringUtil.*;

/**
 * Common actions integration.
 *
 * @author Matt Coley
 */
@ApplicationScoped
public class Actions implements Service {
	public static final String ID = "actions";
	private static final Logger logger = Logging.get(Actions.class);
	private final NavigationManager navigationManager;
	private final DockingManager dockingManager;
	private final TextProviderService textService;
	private final IconProviderService iconService;
	private final Instance<MappingApplier> applierProvider;
	private final Instance<JvmClassPane> jvmPaneProvider;
	private final Instance<AndroidClassPane> androidPaneProvider;
	private final Instance<BinaryXmlFilePane> binaryXmlPaneProvider;
	private final Instance<TextFilePane> textPaneProvider;
	private final Instance<ImageFilePane> imagePaneProvider;
	private final Instance<AudioFilePane> audioPaneProvider;
	private final Instance<VideoFilePane> videoPaneProvider;
	private final Instance<AssemblerPane> assemblerPaneProvider;
	private final ActionsConfig config;

	@Inject
	public Actions(@Nonnull ActionsConfig config,
				   @Nonnull NavigationManager navigationManager,
				   @Nonnull DockingManager dockingManager,
				   @Nonnull TextProviderService textService,
				   @Nonnull IconProviderService iconService,
				   @Nonnull Instance<MappingApplier> applierProvider,
				   @Nonnull Instance<JvmClassPane> jvmPaneProvider,
				   @Nonnull Instance<AndroidClassPane> androidPaneProvider,
				   @Nonnull Instance<BinaryXmlFilePane> binaryXmlPaneProvider,
				   @Nonnull Instance<TextFilePane> textPaneProvider,
				   @Nonnull Instance<ImageFilePane> imagePaneProvider,
				   @Nonnull Instance<AudioFilePane> audioPaneProvider,
				   @Nonnull Instance<VideoFilePane> videoPaneProvider,
				   @Nonnull Instance<AssemblerPane> assemblerPaneProvider) {
		this.config = config;
		this.navigationManager = navigationManager;
		this.dockingManager = dockingManager;
		this.textService = textService;
		this.iconService = iconService;
		this.applierProvider = applierProvider;
		this.jvmPaneProvider = jvmPaneProvider;
		this.androidPaneProvider = androidPaneProvider;
		this.binaryXmlPaneProvider = binaryXmlPaneProvider;
		this.textPaneProvider = textPaneProvider;
		this.imagePaneProvider = imagePaneProvider;
		this.audioPaneProvider = audioPaneProvider;
		this.videoPaneProvider = videoPaneProvider;
		this.assemblerPaneProvider = assemblerPaneProvider;
	}

	/**
	 * Brings a {@link ClassNavigable} component representing the given class into focus.
	 * If no such component exists, one is created.
	 * <br>
	 * Automatically calls the type-specific goto-declaration handling.
	 *
	 * @param path
	 * 		Path containing a class to open.
	 *
	 * @return Navigable content representing class content of the path.
	 *
	 * @throws IncompletePathException
	 * 		When the path is missing parent elements.
	 */
	@Nonnull
	public ClassNavigable gotoDeclaration(@Nonnull ClassPathNode path) throws IncompletePathException {
		Workspace workspace = path.getValueOfType(Workspace.class);
		WorkspaceResource resource = path.getValueOfType(WorkspaceResource.class);
		ClassBundle<?> bundle = path.getValueOfType(ClassBundle.class);
		ClassInfo info = path.getValue();
		if (workspace == null) {
			logger.error("Cannot resolve required path nodes for class '{}', missing workspace in path", info.getName());
			throw new IncompletePathException(Workspace.class);
		}
		if (resource == null) {
			logger.error("Cannot resolve required path nodes for class '{}', missing resource in path", info.getName());
			throw new IncompletePathException(WorkspaceResource.class);
		}
		if (bundle == null) {
			logger.error("Cannot resolve required path nodes for class '{}', missing bundle in path", info.getName());
			throw new IncompletePathException(ClassBundle.class);
		}

		// Handle JVM vs Android
		if (info.isJvmClass()) {
			return gotoDeclaration(workspace, resource, (JvmClassBundle) bundle, info.asJvmClass());
		} else if (info.isAndroidClass()) {
			return gotoDeclaration(workspace, resource, (AndroidClassBundle) bundle, info.asAndroidClass());
		}
		throw new UnsupportedContent("Unsupported class type: " + info.getClass().getName());
	}

	/**
	 * Brings a {@link ClassNavigable} component representing the given class into focus.
	 * If no such component exists, one is created.
	 *
	 * @param workspace
	 * 		Containing workspace.
	 * @param resource
	 * 		Containing resource.
	 * @param bundle
	 * 		Containing bundle.
	 * @param info
	 * 		Class to go to.
	 *
	 * @return Navigable content representing class content of the path.
	 */
	@Nonnull
	public ClassNavigable gotoDeclaration(@Nonnull Workspace workspace,
										  @Nonnull WorkspaceResource resource,
										  @Nonnull JvmClassBundle bundle,
										  @Nonnull JvmClassInfo info) {
		ClassPathNode path = buildPath(workspace, resource, bundle, info);
		return (ClassNavigable) getOrCreatePathContent(path, () -> {
			// Create text/graphic for the tab to create.
			String title = textService.getJvmClassInfoTextProvider(workspace, resource, bundle, info).makeText();
			Node graphic = iconService.getJvmClassInfoIconProvider(workspace, resource, bundle, info).makeIcon();
			if (title == null) throw new IllegalStateException("Missing title");
			if (graphic == null) throw new IllegalStateException("Missing graphic");

			// Create content for the tab.
			JvmClassPane content = jvmPaneProvider.get();
			content.onUpdatePath(path);

			// Build the tab.
			DockingTab tab = createTab(dockingManager.getPrimaryRegion(), title, graphic, content);
			content.addPathUpdateListener(updatedPath -> {
				// Update tab graphic in case backing class details change.
				JvmClassInfo updatedInfo = updatedPath.getValue().asJvmClass();
				String updatedTitle = textService.getJvmClassInfoTextProvider(workspace, resource, bundle, updatedInfo).makeText();
				Node updatedGraphic = iconService.getJvmClassInfoIconProvider(workspace, resource, bundle, updatedInfo).makeIcon();
				FxThreadUtil.run(() -> {
					tab.setText(updatedTitle);
					tab.setGraphic(updatedGraphic);
				});
			});
			ContextMenu menu = new ContextMenu();
			ObservableList<MenuItem> items = menu.getItems();
			Menu mode = menu("menu.mode", CarbonIcons.VIEW);
			mode.getItems().addAll(
					action("menu.mode.class.decompile", CarbonIcons.CODE,
							() -> content.setEditorType(JvmClassEditorType.DECOMPILE)),
					action("menu.mode.file.hex", CarbonIcons.NUMBER_0,
							() -> content.setEditorType(JvmClassEditorType.HEX))
			);
			items.add(mode);
			items.add(action("menu.tab.copypath", CarbonIcons.COPY_LINK, () -> ClipboardUtil.copyString(info)));
			items.add(separator());
			addCloseActions(menu, tab);
			tab.setContextMenu(menu);
			return tab;
		});
	}

	/**
	 * Brings a {@link ClassNavigable} component representing the given class into focus.
	 * If no such component exists, one is created.
	 *
	 * @param workspace
	 * 		Containing workspace.
	 * @param resource
	 * 		Containing resource.
	 * @param bundle
	 * 		Containing bundle.
	 * @param info
	 * 		Class to go to.
	 *
	 * @return Navigable content representing class content of the path.
	 */
	@Nonnull
	public ClassNavigable gotoDeclaration(@Nonnull Workspace workspace,
										  @Nonnull WorkspaceResource resource,
										  @Nonnull AndroidClassBundle bundle,
										  @Nonnull AndroidClassInfo info) {
		ClassPathNode path = buildPath(workspace, resource, bundle, info);
		return (ClassNavigable) getOrCreatePathContent(path, () -> {
			// Create text/graphic for the tab to create.
			String title = textService.getAndroidClassInfoTextProvider(workspace, resource, bundle, info).makeText();
			Node graphic = iconService.getAndroidClassInfoIconProvider(workspace, resource, bundle, info).makeIcon();
			if (title == null) throw new IllegalStateException("Missing title");
			if (graphic == null) throw new IllegalStateException("Missing graphic");

			// Create content for the tab.
			AndroidClassPane content = androidPaneProvider.get();
			content.onUpdatePath(path);

			// Build the tab.
			DockingTab tab = createTab(dockingManager.getPrimaryRegion(), title, graphic, content);
			content.addPathUpdateListener(updatedPath -> {
				// Update tab graphic in case backing class details change.
				AndroidClassInfo updatedInfo = updatedPath.getValue().asAndroidClass();
				String updatedTitle = textService.getAndroidClassInfoTextProvider(workspace, resource, bundle, updatedInfo).makeText();
				Node updatedGraphic = iconService.getAndroidClassInfoIconProvider(workspace, resource, bundle, updatedInfo).makeIcon();
				FxThreadUtil.run(() -> {
					tab.setText(updatedTitle);
					tab.setGraphic(updatedGraphic);
				});
			});
			ContextMenu menu = new ContextMenu();
			ObservableList<MenuItem> items = menu.getItems();
			items.add(action("menu.tab.copypath", CarbonIcons.COPY_LINK, () -> ClipboardUtil.copyString(info)));
			items.add(separator());
			addCloseActions(menu, tab);
			tab.setContextMenu(menu);
			return tab;
		});
	}

	/**
	 * Brings a {@link FileNavigable} component representing the given file into focus.
	 * If no such component exists, one is created.
	 * <br>
	 * Automatically calls the type-specific goto-declaration handling.
	 *
	 * @param path
	 * 		Path containing a file to open.
	 *
	 * @return Navigable content representing file content of the path.
	 *
	 * @throws IncompletePathException
	 * 		When the path is missing parent elements.
	 */
	@Nonnull
	public FileNavigable gotoDeclaration(@Nonnull FilePathNode path) throws IncompletePathException {
		Workspace workspace = path.getValueOfType(Workspace.class);
		WorkspaceResource resource = path.getValueOfType(WorkspaceResource.class);
		FileBundle bundle = path.getValueOfType(FileBundle.class);
		FileInfo info = path.getValue();
		if (workspace == null) {
			logger.error("Cannot resolve required path nodes for file '{}', missing workspace in path", info.getName());
			throw new IncompletePathException(Workspace.class);
		}
		if (resource == null) {
			logger.error("Cannot resolve required path nodes for file '{}', missing resource in path", info.getName());
			throw new IncompletePathException(WorkspaceResource.class);
		}
		if (bundle == null) {
			logger.error("Cannot resolve required path nodes for file '{}', missing bundle in path", info.getName());
			throw new IncompletePathException(ClassBundle.class);
		}

		// Handle text vs binary
		if (info.isTextFile()) {
			return gotoDeclaration(workspace, resource, bundle, info.asTextFile());
		} else if (info.isImageFile()) {
			return gotoDeclaration(workspace, resource, bundle, info.asImageFile());
		} else if (info instanceof BinaryXmlFileInfo binaryXml) {
			return gotoDeclaration(workspace, resource, bundle, binaryXml);
		} else if (info.isAudioFile()) {
			return gotoDeclaration(workspace, resource, bundle, info.asAudioFile());
		} else if (info.isVideoFile()) {
			return gotoDeclaration(workspace, resource, bundle, info.asVideoFile());
		}
		throw new UnsupportedContent("Unsupported file type: " + info.getClass().getName());
	}

	/**
	 * Brings a {@link FileNavigable} component representing the given binary XML file into focus.
	 * If no such component exists, one is created.
	 *
	 * @param workspace
	 * 		Containing workspace.
	 * @param resource
	 * 		Containing resource.
	 * @param bundle
	 * 		Containing bundle.
	 * @param info
	 * 		Binary XML file to go to.
	 *
	 * @return Navigable content representing binary XML file content of the path.
	 */
	@Nonnull
	public FileNavigable gotoDeclaration(@Nonnull Workspace workspace,
										 @Nonnull WorkspaceResource resource,
										 @Nonnull FileBundle bundle,
										 @Nonnull BinaryXmlFileInfo info) {
		FilePathNode path = buildPath(workspace, resource, bundle, info);
		return (FileNavigable) getOrCreatePathContent(path, () -> {
			// Create text/graphic for the tab to create.
			String title = textService.getFileInfoTextProvider(workspace, resource, bundle, info).makeText();
			Node graphic = iconService.getFileInfoIconProvider(workspace, resource, bundle, info).makeIcon();
			if (title == null) throw new IllegalStateException("Missing title");
			if (graphic == null) throw new IllegalStateException("Missing graphic");

			// Create content for the tab.
			BinaryXmlFilePane content = binaryXmlPaneProvider.get();
			content.onUpdatePath(path);

			// Build the tab.
			DockingTab tab = createTab(dockingManager.getPrimaryRegion(), title, graphic, content);
			setupFileTabContextMenu(info, tab);
			return tab;
		});
	}

	/**
	 * Brings a {@link FileNavigable} component representing the given text file into focus.
	 * If no such component exists, one is created.
	 *
	 * @param workspace
	 * 		Containing workspace.
	 * @param resource
	 * 		Containing resource.
	 * @param bundle
	 * 		Containing bundle.
	 * @param info
	 * 		Text file to go to.
	 *
	 * @return Navigable content representing text file content of the path.
	 */
	@Nonnull
	public FileNavigable gotoDeclaration(@Nonnull Workspace workspace,
										 @Nonnull WorkspaceResource resource,
										 @Nonnull FileBundle bundle,
										 @Nonnull TextFileInfo info) {
		FilePathNode path = buildPath(workspace, resource, bundle, info);
		return (FileNavigable) getOrCreatePathContent(path, () -> {
			// Create text/graphic for the tab to create.
			String title = textService.getFileInfoTextProvider(workspace, resource, bundle, info).makeText();
			Node graphic = iconService.getFileInfoIconProvider(workspace, resource, bundle, info).makeIcon();
			if (title == null) throw new IllegalStateException("Missing title");
			if (graphic == null) throw new IllegalStateException("Missing graphic");

			// Create content for the tab.
			TextFilePane content = textPaneProvider.get();
			content.onUpdatePath(path);

			// Build the tab.
			DockingTab tab = createTab(dockingManager.getPrimaryRegion(), title, graphic, content);
			setupFileTabContextMenu(info, tab);
			return tab;
		});
	}

	/**
	 * Brings a {@link FileNavigable} component representing the given image file into focus.
	 * If no such component exists, one is created.
	 *
	 * @param workspace
	 * 		Containing workspace.
	 * @param resource
	 * 		Containing resource.
	 * @param bundle
	 * 		Containing bundle.
	 * @param info
	 * 		Image file to go to.
	 *
	 * @return Navigable content representing image file content of the path.
	 */
	@Nonnull
	public FileNavigable gotoDeclaration(@Nonnull Workspace workspace,
										 @Nonnull WorkspaceResource resource,
										 @Nonnull FileBundle bundle,
										 @Nonnull ImageFileInfo info) {
		FilePathNode path = buildPath(workspace, resource, bundle, info);
		return (FileNavigable) getOrCreatePathContent(path, () -> {
			// Create text/graphic for the tab to create.
			String title = textService.getFileInfoTextProvider(workspace, resource, bundle, info).makeText();
			Node graphic = iconService.getFileInfoIconProvider(workspace, resource, bundle, info).makeIcon();
			if (title == null) throw new IllegalStateException("Missing title");
			if (graphic == null) throw new IllegalStateException("Missing graphic");

			// Create content for the tab.
			ImageFilePane content = imagePaneProvider.get();
			content.onUpdatePath(path);

			// Build the tab.
			DockingTab tab = createTab(dockingManager.getPrimaryRegion(), title, graphic, content);
			setupFileTabContextMenu(info, tab);
			return tab;
		});
	}

	/**
	 * Brings a {@link FileNavigable} component representing the given audio file into focus.
	 * If no such component exists, one is created.
	 *
	 * @param workspace
	 * 		Containing workspace.
	 * @param resource
	 * 		Containing resource.
	 * @param bundle
	 * 		Containing bundle.
	 * @param info
	 * 		Image file to go to.
	 *
	 * @return Navigable content representing audio file content of the path.
	 */
	@Nonnull
	public FileNavigable gotoDeclaration(@Nonnull Workspace workspace,
										 @Nonnull WorkspaceResource resource,
										 @Nonnull FileBundle bundle,
										 @Nonnull AudioFileInfo info) {
		FilePathNode path = buildPath(workspace, resource, bundle, info);
		return (FileNavigable) getOrCreatePathContent(path, () -> {
			// Create text/graphic for the tab to create.
			String title = textService.getFileInfoTextProvider(workspace, resource, bundle, info).makeText();
			Node graphic = iconService.getFileInfoIconProvider(workspace, resource, bundle, info).makeIcon();
			if (title == null) throw new IllegalStateException("Missing title");
			if (graphic == null) throw new IllegalStateException("Missing graphic");

			// Create content for the tab.
			AudioFilePane content = audioPaneProvider.get();
			content.onUpdatePath(path);

			// Build the tab.
			DockingTab tab = createTab(dockingManager.getPrimaryRegion(), title, graphic, content);
			setupFileTabContextMenu(info, tab);
			return tab;
		});
	}

	/**
	 * Brings a {@link FileNavigable} component representing the given vdeo file into focus.
	 * If no such component exists, one is created.
	 *
	 * @param workspace
	 * 		Containing workspace.
	 * @param resource
	 * 		Containing resource.
	 * @param bundle
	 * 		Containing bundle.
	 * @param info
	 * 		Image file to go to.
	 *
	 * @return Navigable content representing video file content of the path.
	 */
	@Nonnull
	public FileNavigable gotoDeclaration(@Nonnull Workspace workspace,
										 @Nonnull WorkspaceResource resource,
										 @Nonnull FileBundle bundle,
										 @Nonnull VideoFileInfo info) {
		FilePathNode path = buildPath(workspace, resource, bundle, info);
		return (FileNavigable) getOrCreatePathContent(path, () -> {
			// Create text/graphic for the tab to create.
			String title = textService.getFileInfoTextProvider(workspace, resource, bundle, info).makeText();
			Node graphic = iconService.getFileInfoIconProvider(workspace, resource, bundle, info).makeIcon();
			if (title == null) throw new IllegalStateException("Missing title");
			if (graphic == null) throw new IllegalStateException("Missing graphic");

			// Create content for the tab.
			VideoFilePane content = videoPaneProvider.get();
			content.onUpdatePath(path);

			// Build the tab.
			DockingTab tab = createTab(dockingManager.getPrimaryRegion(), title, graphic, content);
			setupFileTabContextMenu(info, tab);
			return tab;
		});
	}

	/**
	 * Prompts the user to select a package to move the given class into.
	 *
	 * @param workspace
	 * 		Containing workspace.
	 * @param resource
	 * 		Containing resource.
	 * @param bundle
	 * 		Containing bundle.
	 * @param info
	 * 		Class to go move into a different package.
	 */
	public void moveClass(@Nonnull Workspace workspace,
						  @Nonnull WorkspaceResource resource,
						  @Nonnull JvmClassBundle bundle,
						  @Nonnull JvmClassInfo info) {
		boolean isRootDirectory = isNullOrEmpty(info.getPackageName());
		ItemTreeSelectionPopup.forPackageNames(bundle, packages -> {
					// We only allow a single package, so the list should contain just one item.
					String oldPackage = isRootDirectory ? "" : info.getPackageName() + "/";
					String newPackage = packages.get(0);
					if (Objects.equals(oldPackage, newPackage)) return;
					if (!newPackage.isEmpty()) newPackage += "/";

					// Create mapping for the class and any inner classes.
					String originalName = info.getName();
					String newName = replacePrefix(originalName, oldPackage, newPackage);
					IntermediateMappings mappings = new IntermediateMappings();
					for (InnerClassInfo inner : info.getInnerClasses()) {
						if (inner.isExternalReference()) continue;
						String innerClassName = inner.getInnerClassName();
						mappings.addClass(innerClassName, newName + innerClassName.substring(originalName.length()));
					}

					// Apply the mappings.
					MappingApplier applier = applierProvider.get();
					MappingResults results = applier.applyToPrimaryResource(mappings);
					results.apply();
				})
				.withTitle(Lang.getBinding("dialog.title.move-class"))
				.withTextMapping(name -> textService.getPackageTextProvider(workspace, resource, bundle, name).makeText())
				.withGraphicMapping(name -> iconService.getPackageIconProvider(workspace, resource, bundle, name).makeIcon())
				.show();
	}

	/**
	 * Prompts the user to select a directory to move the given file into.
	 *
	 * @param workspace
	 * 		Containing workspace.
	 * @param resource
	 * 		Containing resource.
	 * @param bundle
	 * 		Containing bundle.
	 * @param info
	 * 		File to move into a different directory.
	 */
	public void moveFile(@Nonnull Workspace workspace,
						 @Nonnull WorkspaceResource resource,
						 @Nonnull FileBundle bundle,
						 @Nonnull FileInfo info) {
		boolean isRootDirectory = isNullOrEmpty(info.getDirectoryName());
		ItemTreeSelectionPopup.forDirectoryNames(bundle, chosenDirectories -> {
					// We only allow a single directory, so the list should contain just one item.
					if (chosenDirectories.isEmpty()) return;
					String oldDirectoryName = isRootDirectory ? "" : info.getDirectoryName() + "/";
					String newDirectoryName = chosenDirectories.get(0);
					if (Objects.equals(oldDirectoryName, newDirectoryName)) return;
					if (!newDirectoryName.isEmpty()) newDirectoryName += "/";

					String newName = replacePrefix(info.getName(), oldDirectoryName, newDirectoryName);

					bundle.remove(info.getName());
					bundle.put(info.toFileBuilder().withName(newName).build());
				}).withTitle(Lang.getBinding("dialog.title.move-file"))
				.withTextMapping(name -> textService.getDirectoryTextProvider(workspace, resource, bundle, name).makeText())
				.withGraphicMapping(name -> iconService.getDirectoryIconProvider(workspace, resource, bundle, name).makeIcon())
				.show();
	}

	/**
	 * Prompts the user to select a package to move the given package into.
	 *
	 * @param workspace
	 * 		Containing workspace.
	 * @param resource
	 * 		Containing resource.
	 * @param bundle
	 * 		Containing bundle.
	 * @param packageName
	 * 		Package to go move into another package as a sub-package.
	 */
	public void movePackage(@Nonnull Workspace workspace,
							@Nonnull WorkspaceResource resource,
							@Nonnull JvmClassBundle bundle,
							@Nonnull String packageName) {
		boolean isRootDirectory = packageName.isEmpty();
		ItemTreeSelectionPopup.forPackageNames(bundle, chosenPackages -> {
					if (chosenPackages.isEmpty()) return;
					String newPackageName = chosenPackages.get(0);
					if (packageName.equals(newPackageName)) return;

					// Create mappings for classes in the given package.
					IntermediateMappings mappings = new IntermediateMappings();
					String newPrefix = (newPackageName.isEmpty() ? "" : newPackageName + "/") + shortenPath(packageName) + "/";
					if (isRootDirectory) {
						// Source is default package
						for (JvmClassInfo info : bundle.values()) {
							String name = info.getName();
							if (name.indexOf('/') != -1) {
								mappings.addClass(name, newPrefix + name);
							}
						}
					} else {
						// Source is another package
						String oldPrefix = packageName + "/";
						for (JvmClassInfo info : bundle.values()) {
							String name = info.getName();
							if (newPackageName.isEmpty() && name.indexOf('/') == -1) {
								// Target is default package
								mappings.addClass(name, shortenPath(name));
							} else if (name.startsWith(oldPrefix)) {
								// Target is some package, replace prefix
								mappings.addClass(name, replacePrefix(name, oldPrefix, newPrefix));
							}
						}
					}

					// Apply the mappings.
					MappingApplier applier = applierProvider.get();
					MappingResults results = applier.applyToPrimaryResource(mappings);
					results.apply();
				}).withTitle(Lang.getBinding("dialog.title.move-package"))
				.withTextMapping(name -> textService.getPackageTextProvider(workspace, resource, bundle, name).makeText())
				.withGraphicMapping(name -> iconService.getPackageIconProvider(workspace, resource, bundle, name).makeIcon())
				.show();
	}

	/**
	 * Prompts the user to select a directory to move the given directory into.
	 *
	 * @param workspace
	 * 		Containing workspace.
	 * @param resource
	 * 		Containing resource.
	 * @param bundle
	 * 		Containing bundle.
	 * @param directoryName
	 * 		Directory to go move into another directory as a sub-directory.
	 */
	public void moveDirectory(@Nonnull Workspace workspace,
							  @Nonnull WorkspaceResource resource,
							  @Nonnull FileBundle bundle,
							  @Nonnull String directoryName) {
		boolean isRootDirectory = directoryName.isEmpty();
		String localDirectoryName = shortenPath(directoryName);
		ItemTreeSelectionPopup.forDirectoryNames(bundle, chosenDirectories -> {
					if (chosenDirectories.isEmpty()) return;
					String newDirectoryName = chosenDirectories.get(0);
					if (directoryName.equals(newDirectoryName)) return;

					String prefix = directoryName + "/";
					for (FileInfo value : bundle.valuesAsCopy()) {
						String filePath = value.getName();
						String fileName = shortenPath(filePath);

						// Source is root directory, this file is also in the root directory
						if (isRootDirectory && filePath.indexOf('/') == -1) {
							String name = newDirectoryName + "/" + fileName;
							bundle.remove(filePath);
							bundle.put(value.toFileBuilder().withName(name).build());
						} else {
							// Source is another package, this file matches that package
							if (filePath.startsWith(prefix)) {
								String name;
								if (newDirectoryName.isEmpty()) {
									// Target is root directory
									name = localDirectoryName + "/" + fileName;
								} else if (filePath.startsWith(directoryName)) {
									// Target is another directory
									name = replacePrefix(filePath, directoryName, newDirectoryName + "/" + localDirectoryName);
								} else {
									continue;
								}
								bundle.remove(filePath);
								bundle.put(value.toFileBuilder().withName(name).build());
							}
						}
					}
				}).withTitle(Lang.getBinding("dialog.title.move-directory"))
				.withTextMapping(name -> textService.getDirectoryTextProvider(workspace, resource, bundle, name).makeText())
				.withGraphicMapping(name -> iconService.getDirectoryIconProvider(workspace, resource, bundle, name).makeIcon())
				.show();
	}

	/**
	 * Prompts the user to rename whatever sort of content is contained within the given path.
	 *
	 * @param path
	 * 		Item to rename. Can be a number of values.
	 */
	public void rename(@Nonnull PathNode<?> path) {
		// Handle renaming based on the different resolved content type.
		if (path instanceof ClassPathNode classPath)
			Unchecked.run(() -> renameClass(classPath));
		else if (path instanceof ClassMemberPathNode memberPathNode)
			if (memberPathNode.isField())
				Unchecked.run(() -> renameField(memberPathNode));
			else
				Unchecked.run(() -> renameMethod(memberPathNode));
		else if (path instanceof FilePathNode filePath)
			Unchecked.run(() -> renameFile(filePath));
		else if (path instanceof DirectoryPathNode directoryPath)
			Unchecked.run(() -> renamePackageOrDirectory(directoryPath));
	}

	/**
	 * Prompts the user to rename the given class.
	 *
	 * @param path
	 * 		Path to class.
	 *
	 * @throws IncompletePathException
	 * 		When the path is missing parent elements.
	 */
	public void renameClass(@Nonnull ClassPathNode path) throws IncompletePathException {
		Workspace workspace = path.getValueOfType(Workspace.class);
		WorkspaceResource resource = path.getValueOfType(WorkspaceResource.class);
		ClassBundle<?> bundle = path.getValueOfType(ClassBundle.class);
		ClassInfo info = path.getValue();
		if (workspace == null) {
			logger.error("Cannot resolve required path nodes for class '{}', missing workspace in path", info.getName());
			throw new IncompletePathException(Workspace.class);
		}
		if (resource == null) {
			logger.error("Cannot resolve required path nodes for class '{}', missing resource in path", info.getName());
			throw new IncompletePathException(WorkspaceResource.class);
		}
		if (bundle == null) {
			logger.error("Cannot resolve required path nodes for class '{}', missing bundle in path", info.getName());
			throw new IncompletePathException(ClassBundle.class);
		}
		renameClass(workspace, resource, bundle, info);
	}

	/**
	 * Prompts the user to rename the given class.
	 *
	 * @param workspace
	 * 		Containing workspace.
	 * @param resource
	 * 		Containing resource.
	 * @param bundle
	 * 		Containing bundle.
	 * @param info
	 * 		Class to rename.
	 */
	public void renameClass(@Nonnull Workspace workspace,
							@Nonnull WorkspaceResource resource,
							@Nonnull ClassBundle<? extends ClassInfo> bundle,
							@Nonnull ClassInfo info) {
		String originalName = info.getName();
		Consumer<String> renameTask = newName -> {
			// Create mapping for the class and any inner classes.
			IntermediateMappings mappings = new IntermediateMappings();
			mappings.addClass(originalName, newName);
			for (InnerClassInfo inner : info.getInnerClasses()) {
				if (inner.isExternalReference()) continue;
				String innerClassName = inner.getInnerClassName();
				mappings.addClass(innerClassName, newName + innerClassName.substring(originalName.length()));
			}

			// Apply the mappings.
			MappingApplier applier = applierProvider.get();
			MappingResults results = applier.applyToPrimaryResource(mappings);
			results.apply();
		};
		new NamePopup(renameTask)
				.withInitialPathName(originalName)
				.forClassRename(bundle)
				.show();
	}

	/**
	 * Prompts the user to rename the given field.
	 *
	 * @param path
	 * 		Path to field.
	 *
	 * @throws IncompletePathException
	 * 		When the path is missing parent elements.
	 */
	public void renameField(@Nonnull ClassMemberPathNode path) throws IncompletePathException {
		Workspace workspace = path.getValueOfType(Workspace.class);
		WorkspaceResource resource = path.getValueOfType(WorkspaceResource.class);
		ClassBundle<?> bundle = path.getValueOfType(ClassBundle.class);
		ClassInfo declaringClass = path.getValueOfType(ClassInfo.class);
		FieldMember fieldMember = (FieldMember) path.getValue();
		if (workspace == null) {
			logger.error("Cannot resolve required path nodes for field '{}', missing workspace in path", fieldMember.getName());
			throw new IncompletePathException(Workspace.class);
		}
		if (resource == null) {
			logger.error("Cannot resolve required path nodes for field '{}', missing resource in path", fieldMember.getName());
			throw new IncompletePathException(WorkspaceResource.class);
		}
		if (bundle == null) {
			logger.error("Cannot resolve required path nodes for field '{}', missing bundle in path", fieldMember.getName());
			throw new IncompletePathException(ClassBundle.class);
		}
		if (declaringClass == null) {
			logger.error("Cannot resolve required path nodes for field '{}', missing class in path", fieldMember.getName());
			throw new IncompletePathException(ClassBundle.class);
		}
		renameField(workspace, resource, bundle, declaringClass, fieldMember);
	}

	/**
	 * Prompts the user to rename the given field.
	 *
	 * @param workspace
	 * 		Containing workspace.
	 * @param resource
	 * 		Containing resource.
	 * @param bundle
	 * 		Containing bundle.
	 * @param declaringClass
	 * 		Class containing the field.
	 * @param field
	 * 		Field to rename.
	 */
	public void renameField(@Nonnull Workspace workspace,
							@Nonnull WorkspaceResource resource,
							@Nonnull ClassBundle<? extends ClassInfo> bundle,
							@Nonnull ClassInfo declaringClass,
							@Nonnull FieldMember field) {
		String originalName = field.getName();
		Consumer<String> renameTask = newName -> {
			IntermediateMappings mappings = new IntermediateMappings();
			mappings.addField(declaringClass.getName(), field.getDescriptor(), originalName, newName);

			// Apply the mappings.
			MappingApplier applier = applierProvider.get();
			MappingResults results = applier.applyToPrimaryResource(mappings);
			results.apply();
		};
		new NamePopup(renameTask)
				.withInitialName(originalName)
				.forFieldRename(declaringClass, field)
				.show();
	}

	/**
	 * Prompts the user to rename the given method.
	 *
	 * @param path
	 * 		Path to method.
	 *
	 * @throws IncompletePathException
	 * 		When the path is missing parent elements.
	 */
	public void renameMethod(@Nonnull ClassMemberPathNode path) throws IncompletePathException {
		Workspace workspace = path.getValueOfType(Workspace.class);
		WorkspaceResource resource = path.getValueOfType(WorkspaceResource.class);
		ClassBundle<?> bundle = path.getValueOfType(ClassBundle.class);
		ClassInfo declaringClass = path.getValueOfType(ClassInfo.class);
		MethodMember methodMember = (MethodMember) path.getValue();
		if (workspace == null) {
			logger.error("Cannot resolve required path nodes for method '{}', missing workspace in path", methodMember.getName());
			throw new IncompletePathException(Workspace.class);
		}
		if (resource == null) {
			logger.error("Cannot resolve required path nodes for method '{}', missing resource in path", methodMember.getName());
			throw new IncompletePathException(WorkspaceResource.class);
		}
		if (bundle == null) {
			logger.error("Cannot resolve required path nodes for method '{}', missing bundle in path", methodMember.getName());
			throw new IncompletePathException(ClassBundle.class);
		}
		if (declaringClass == null) {
			logger.error("Cannot resolve required path nodes for method '{}', missing class in path", methodMember.getName());
			throw new IncompletePathException(ClassBundle.class);
		}
		renameMethod(workspace, resource, bundle, declaringClass, methodMember);
	}

	/**
	 * Prompts the user to rename the given method.
	 *
	 * @param workspace
	 * 		Containing workspace.
	 * @param resource
	 * 		Containing resource.
	 * @param bundle
	 * 		Containing bundle.
	 * @param declaringClass
	 * 		Class containing the method.
	 * @param method
	 * 		Method to rename.
	 */
	public void renameMethod(@Nonnull Workspace workspace,
							 @Nonnull WorkspaceResource resource,
							 @Nonnull ClassBundle<? extends ClassInfo> bundle,
							 @Nonnull ClassInfo declaringClass,
							 @Nonnull MethodMember method) {
		String originalName = method.getName();
		Consumer<String> renameTask = newName -> {
			IntermediateMappings mappings = new IntermediateMappings();
			mappings.addMethod(declaringClass.getName(), method.getDescriptor(), originalName, newName);

			// Apply the mappings.
			MappingApplier applier = applierProvider.get();
			MappingResults results = applier.applyToPrimaryResource(mappings);
			results.apply();
		};
		new NamePopup(renameTask)
				.withInitialName(originalName)
				.forMethodRename(declaringClass, method)
				.show();
	}

	/**
	 * Prompts the user to rename the given field.
	 *
	 * @param path
	 * 		Path to file.
	 *
	 * @throws IncompletePathException
	 * 		When the path is missing parent elements.
	 */
	public void renameFile(@Nonnull FilePathNode path) throws IncompletePathException {
		Workspace workspace = path.getValueOfType(Workspace.class);
		WorkspaceResource resource = path.getValueOfType(WorkspaceResource.class);
		FileBundle bundle = path.getValueOfType(FileBundle.class);
		FileInfo info = path.getValue();
		if (workspace == null) {
			logger.error("Cannot resolve required path nodes for file '{}', missing workspace in path", info.getName());
			throw new IncompletePathException(Workspace.class);
		}
		if (resource == null) {
			logger.error("Cannot resolve required path nodes for file '{}', missing resource in path", info.getName());
			throw new IncompletePathException(WorkspaceResource.class);
		}
		if (bundle == null) {
			logger.error("Cannot resolve required path nodes for file '{}', missing bundle in path", info.getName());
			throw new IncompletePathException(ClassBundle.class);
		}
		renameFile(workspace, resource, bundle, info);
	}

	/**
	 * Prompts the user to rename the given file.
	 *
	 * @param workspace
	 * 		Containing workspace.
	 * @param resource
	 * 		Containing resource.
	 * @param bundle
	 * 		Containing bundle.
	 * @param info
	 * 		File to rename.
	 */
	public void renameFile(@Nonnull Workspace workspace,
						   @Nonnull WorkspaceResource resource,
						   @Nonnull FileBundle bundle,
						   @Nonnull FileInfo info) {
		String name = info.getName();
		new NamePopup(newFileName -> {
			if (name.equals(newFileName)) return;
			bundle.remove(name);
			bundle.put(info.toFileBuilder().withName(newFileName).build());
		}).withInitialPathName(name)
				.forFileRename(bundle)
				.show();
	}

	/**
	 * Prompts the user to rename the given directory.
	 *
	 * @param path
	 * 		Path to directory.
	 *
	 * @throws IncompletePathException
	 * 		When the path is missing parent elements.
	 */
	public void renamePackageOrDirectory(@Nonnull DirectoryPathNode path) throws IncompletePathException {
		String directoryName = path.getValue();

		Workspace workspace = path.getValueOfType(Workspace.class);
		WorkspaceResource resource = path.getValueOfType(WorkspaceResource.class);
		Bundle<?> bundle = path.getValueOfType(Bundle.class);
		if (workspace == null) {
			logger.error("Cannot resolve required path nodes for directory '{}', missing workspace in path", directoryName);
			throw new IncompletePathException(Workspace.class);
		}
		if (resource == null) {
			logger.error("Cannot resolve required path nodes for directory '{}', missing resource in path", directoryName);
			throw new IncompletePathException(WorkspaceResource.class);
		}
		if (bundle == null) {
			logger.error("Cannot resolve required path nodes for directory '{}', missing bundle in path", directoryName);
			throw new IncompletePathException(ClassBundle.class);
		}

		if (bundle instanceof FileBundle fileBundle)
			renameDirectory(workspace, resource, fileBundle, directoryName);
		else if (bundle instanceof JvmClassBundle classBundle)
			renamePackage(workspace, resource, classBundle, directoryName);
	}

	/**
	 * Prompts the user to rename the given directory.
	 *
	 * @param workspace
	 * 		Containing workspace.
	 * @param resource
	 * 		Containing resource.
	 * @param bundle
	 * 		Containing bundle.
	 * @param directoryName
	 * 		Name of directory to rename.
	 */
	public void renameDirectory(@Nonnull Workspace workspace,
								@Nonnull WorkspaceResource resource,
								@Nonnull FileBundle bundle,
								@Nonnull String directoryName) {
		boolean isRootDirectory = directoryName.isEmpty();
		new NamePopup(newDirectoryName -> {
			if (directoryName.equals(newDirectoryName)) return;

			String prefix = directoryName + "/";
			for (FileInfo value : bundle.valuesAsCopy()) {
				String filePath = value.getName();
				String fileName = shortenPath(filePath);

				// Source is root directory, this file is also in the root directory
				if (isRootDirectory && filePath.indexOf('/') == -1) {
					String name = newDirectoryName + "/" + fileName;
					bundle.remove(filePath);
					bundle.put(value.toFileBuilder().withName(name).build());
				} else {
					// Source is another package, this file matches that package
					if (filePath.startsWith(prefix)) {
						String name;
						if (newDirectoryName.isEmpty()) {
							// Target is root directory
							name = fileName;
						} else if (filePath.startsWith(directoryName)) {
							// Target is another directory
							name = replacePrefix(filePath, directoryName, newDirectoryName);
						} else {
							continue;
						}
						bundle.remove(filePath);
						bundle.put(value.toFileBuilder().withName(name).build());
					}
				}
			}
		}).withInitialPathName(directoryName)
				.forDirectoryRename(bundle)
				.show();
	}

	/**
	 * Prompts the user to give a new name for the copied package.
	 *
	 * @param workspace
	 * 		Containing workspace.
	 * @param resource
	 * 		Containing resource.
	 * @param bundle
	 * 		Containing bundle.
	 * @param packageName
	 * 		Name of directory to copy.
	 */
	public void renamePackage(@Nonnull Workspace workspace,
							  @Nonnull WorkspaceResource resource,
							  @Nonnull JvmClassBundle bundle,
							  @Nonnull String packageName) {
		boolean isRootDirectory = packageName.isEmpty();
		new NamePopup(newPackageName -> {
			// Create mappings.
			String oldPrefix = isRootDirectory ? "" : packageName + "/";
			String newPrefix = newPackageName + "/";
			IntermediateMappings mappings = new IntermediateMappings();
			for (JvmClassInfo info : bundle.valuesAsCopy()) {
				String className = info.getName();
				if (isRootDirectory) {
					// Source is the default package
					if (className.indexOf('/') == -1)
						// Class is in the default package
						mappings.addClass(className, newPackageName + '/' + className);
				} else if (className.startsWith(oldPrefix))
					// Class starts with the package prefix
					mappings.addClass(className, replacePrefix(className, oldPrefix, newPrefix));
			}

			// Apply mappings to create copies of the affected classes, using the provided name.
			// Then dump the mapped classes into bundle.
			MappingApplier applier = applierProvider.get();
			MappingResults results = applier.applyToPrimaryResource(mappings);
			results.apply();
		}).withInitialPathName(packageName)
				.forPackageRename(bundle)
				.show();
	}

	/**
	 * Prompts the user to give a new name for the copied class.
	 * Inner classes also get copied.
	 *
	 * @param workspace
	 * 		Containing workspace.
	 * @param resource
	 * 		Containing resource.
	 * @param bundle
	 * 		Containing bundle.
	 * @param info
	 * 		Class to copy.
	 */
	public void copyClass(@Nonnull Workspace workspace,
						  @Nonnull WorkspaceResource resource,
						  @Nonnull JvmClassBundle bundle,
						  @Nonnull JvmClassInfo info) {
		String originalName = info.getName();
		Consumer<String> copyTask = newName -> {
			// Create mappings.
			IntermediateMappings mappings = new IntermediateMappings();
			mappings.addClass(originalName, newName);

			// Collect inner classes, we need to copy these as well.
			List<JvmClassInfo> classesToCopy = new ArrayList<>();
			classesToCopy.add(info);
			for (InnerClassInfo inner : info.getInnerClasses()) {
				if (inner.isExternalReference()) continue;
				String innerClassName = inner.getInnerClassName();
				mappings.addClass(innerClassName, newName + innerClassName.substring(originalName.length()));
				JvmClassInfo innerClassInfo = bundle.get(innerClassName);
				if (innerClassInfo != null)
					classesToCopy.add(innerClassInfo);
				else
					logger.warn("Could not find inner class for copy-operation: {}", EscapeUtil.escapeStandard(innerClassName));
			}

			// Apply mappings to create copies of the affected classes, using the provided name.
			// Then dump the mapped classes into bundle.
			MappingApplier applier = applierProvider.get();
			MappingResults results = applier.applyToClasses(mappings, resource, bundle, classesToCopy);
			for (ClassPathNode mappedClassPath : results.getPostMappingPaths().values()) {
				JvmClassInfo mappedClass = mappedClassPath.getValue().asJvmClass();
				bundle.put(mappedClass);
			}
		};
		new NamePopup(copyTask)
				.withInitialPathName(originalName)
				.forClassCopy(bundle)
				.show();
	}

	/**
	 * Prompts the user to give a new name for the copied member.
	 *
	 * @param workspace
	 * 		Containing workspace.
	 * @param resource
	 * 		Containing resource.
	 * @param bundle
	 * 		Containing bundle.
	 * @param declaringClass
	 * 		Containing class.
	 * @param member
	 * 		member to copy.
	 */
	public void copyMember(@Nonnull Workspace workspace,
						   @Nonnull WorkspaceResource resource,
						   @Nonnull JvmClassBundle bundle,
						   @Nonnull JvmClassInfo declaringClass,
						   @Nonnull ClassMember member) {
		String originalName = member.getName();
		Consumer<String> copyTask = newName -> {
			ClassWriter cw = new ClassWriter(0);
			MemberCopyingVisitor cp = new MemberCopyingVisitor(cw, member, newName);
			declaringClass.getClassReader().accept(cp, 0);
			bundle.put(new JvmClassInfoBuilder(cw.toByteArray()).build());
		};
		new NamePopup(copyTask)
				.withInitialName(originalName)
				.forFieldCopy(declaringClass, member)
				.show();
	}

	/**
	 * Prompts the user to give a new name for the copied file.
	 *
	 * @param workspace
	 * 		Containing workspace.
	 * @param resource
	 * 		Containing resource.
	 * @param bundle
	 * 		Containing bundle.
	 * @param info
	 * 		File to copy.
	 */
	public void copyFile(@Nonnull Workspace workspace,
						 @Nonnull WorkspaceResource resource,
						 @Nonnull FileBundle bundle,
						 @Nonnull FileInfo info) {
		new NamePopup(newName -> {
			if (info.getName().equals(newName)) return;
			bundle.put(info.toFileBuilder().withName(newName).build());
		}).withInitialPathName(info.getName())
				.forDirectoryCopy(bundle)
				.show();
	}

	/**
	 * Prompts the user to give a new name for the copied directory.
	 *
	 * @param workspace
	 * 		Containing workspace.
	 * @param resource
	 * 		Containing resource.
	 * @param bundle
	 * 		Containing bundle.
	 * @param directoryName
	 * 		Name of directory to copy.
	 */
	public void copyDirectory(@Nonnull Workspace workspace,
							  @Nonnull WorkspaceResource resource,
							  @Nonnull FileBundle bundle,
							  @Nonnull String directoryName) {
		boolean isRootDirectory = directoryName.isEmpty();
		new NamePopup(newDirectoryName -> {
			if (directoryName.equals(newDirectoryName)) return;
			for (FileInfo value : bundle.valuesAsCopy()) {
				String path = value.getName();
				if (isRootDirectory) {
					if (path.indexOf('/') == -1) {
						String name = newDirectoryName + "/" + path;
						bundle.put(value.toFileBuilder().withName(name).build());
					}
				} else {
					if (path.startsWith(directoryName)) {
						String name = replacePrefix(path, directoryName, newDirectoryName);
						bundle.put(value.toFileBuilder().withName(name).build());
					}
				}
			}
		}).withInitialPathName(directoryName)
				.forDirectoryCopy(bundle)
				.show();
	}

	/**
	 * Prompts the user to give a new name for the copied package.
	 *
	 * @param workspace
	 * 		Containing workspace.
	 * @param resource
	 * 		Containing resource.
	 * @param bundle
	 * 		Containing bundle.
	 * @param packageName
	 * 		Name of directory to copy.
	 */
	public void copyPackage(@Nonnull Workspace workspace,
							@Nonnull WorkspaceResource resource,
							@Nonnull JvmClassBundle bundle,
							@Nonnull String packageName) {
		boolean isRootDirectory = packageName.isEmpty();
		new NamePopup(newPackageName -> {
			// Create mappings.
			String oldPrefix = isRootDirectory ? "" : packageName + "/";
			String newPrefix = newPackageName + "/";
			IntermediateMappings mappings = new IntermediateMappings();
			List<JvmClassInfo> classesToCopy = new ArrayList<>();
			for (JvmClassInfo info : bundle.valuesAsCopy()) {
				String className = info.getName();
				if (isRootDirectory) {
					if (className.indexOf('/') == -1) {
						mappings.addClass(className, newPrefix + className);
						classesToCopy.add(info);
					}
				} else if (className.startsWith(oldPrefix)) {
					mappings.addClass(className, replacePrefix(className, oldPrefix, newPrefix));
					classesToCopy.add(info);
				}
			}

			// Apply mappings to create copies of the affected classes, using the provided name.
			// Then dump the mapped classes into bundle.
			MappingApplier applier = applierProvider.get();
			MappingResults results = applier.applyToClasses(mappings, resource, bundle, classesToCopy);
			for (ClassPathNode mappedClassPath : results.getPostMappingPaths().values()) {
				JvmClassInfo mappedClass = mappedClassPath.getValue().asJvmClass();
				bundle.put(mappedClass);
			}
		}).withInitialPathName(packageName)
				.forPackageCopy(bundle)
				.show();
	}

	/**
	 * Brings a {@link ClassNavigable} component representing the given class into focus.
	 * If no such component exists, one is created.
	 * <br>
	 * Automatically calls the type-specific goto-declaration handling.
	 *
	 * @param path
	 * 		Path containing a class to open.
	 *
	 * @return Navigable content representing class content of the path.
	 *
	 * @throws IncompletePathException
	 * 		When the path is missing parent elements.
	 */
	@Nonnull
	public Navigable openAssembler(@Nonnull PathNode<?> path) throws IncompletePathException {
		Workspace workspace = path.getValueOfType(Workspace.class);
		WorkspaceResource resource = path.getValueOfType(WorkspaceResource.class);
		ClassBundle<?> bundle = path.getValueOfType(ClassBundle.class);
		ClassInfo info = path.getValueOfType(ClassInfo.class);
		if (info == null) {
			logger.error("Cannot resolve required path nodes, missing class in path");
			throw new IncompletePathException(ClassInfo.class);
		}
		if (workspace == null) {
			logger.error("Cannot resolve required path nodes for class '{}', missing workspace in path", info.getName());
			throw new IncompletePathException(Workspace.class);
		}
		if (resource == null) {
			logger.error("Cannot resolve required path nodes for class '{}', missing resource in path", info.getName());
			throw new IncompletePathException(WorkspaceResource.class);
		}
		if (bundle == null) {
			logger.error("Cannot resolve required path nodes for class '{}', missing bundle in path", info.getName());
			throw new IncompletePathException(ClassBundle.class);
		}

		return createContent(() -> {
			// Create text/graphic for the tab to create.
			String name = "?";
			if (path instanceof ClassPathNode classPathNode)
				name = StringUtil.shortenPath(classPathNode.getValue().getName());
			else if (path instanceof ClassMemberPathNode classMemberPathNode)
				name = classMemberPathNode.getValue().getName();
			String title = "Assembler: " + name;
			Node graphic = new FontIconView(CarbonIcons.CODE);

			// Create content for the tab.
			AssemblerPane content = assemblerPaneProvider.get();
			content.onUpdatePath(path);

			// Build the tab.
			return createTab(dockingManager.getPrimaryRegion(), title, graphic, content);
		});
	}

	/**
	 * Prompts the user <i>(if configured, otherwise prompt is skipped)</i> to delete the class.
	 *
	 * @param workspace
	 * 		Containing workspace.
	 * @param resource
	 * 		Containing resource.
	 * @param bundle
	 * 		Containing bundle.
	 * @param info
	 * 		Class to delete.
	 */
	public void deleteClass(@Nonnull Workspace workspace,
							@Nonnull WorkspaceResource resource,
							@Nonnull JvmClassBundle bundle,
							@Nonnull JvmClassInfo info) {
		// TODO: Ask user if they are sure
		//  - Use config to check if "are you sure" prompts should be bypassed
		bundle.remove(info.getName());
	}

	/**
	 * Prompts the user <i>(if configured, otherwise prompt is skipped)</i> to delete the file.
	 *
	 * @param workspace
	 * 		Containing workspace.
	 * @param resource
	 * 		Containing resource.
	 * @param bundle
	 * 		Containing bundle.
	 * @param info
	 * 		File to delete.
	 */
	public void deleteFile(@Nonnull Workspace workspace,
						   @Nonnull WorkspaceResource resource,
						   @Nonnull FileBundle bundle,
						   @Nonnull FileInfo info) {
		// TODO: Ask user if they are sure
		//  - Use config to check if "are you sure" prompts should be bypassed
		bundle.remove(info.getName());
	}

	/**
	 * Prompts the user <i>(if configured, otherwise prompt is skipped)</i> to delete the package.
	 *
	 * @param workspace
	 * 		Containing workspace.
	 * @param resource
	 * 		Containing resource.
	 * @param bundle
	 * 		Containing bundle.
	 * @param packageName
	 * 		Name of package to delete.
	 */
	public void deletePackage(@Nonnull Workspace workspace,
							  @Nonnull WorkspaceResource resource,
							  @Nonnull ClassBundle<?> bundle,
							  @Nonnull String packageName) {
		// TODO: Ask user if they are sure
		//  - Use config to check if "are you sure" prompts should be bypassed
		boolean isRootDirectory = packageName.isEmpty();
		String packageNamePrefix = packageName + "/";
		for (ClassInfo value : bundle.valuesAsCopy()) {
			String path = value.getName();
			if (isRootDirectory) {
				// Source is in the default package, and the current class is also in the default package.
				if (path.indexOf('/') == -1) {
					bundle.remove(path);
				}
			} else {
				// Source is in a package, and the current class is in the same package.
				if (path.startsWith(packageNamePrefix)) {
					bundle.remove(path);
				}
			}
		}
	}

	/**
	 * Prompts the user <i>(if configured, otherwise prompt is skipped)</i> to delete the directory.
	 *
	 * @param workspace
	 * 		Containing workspace.
	 * @param resource
	 * 		Containing resource.
	 * @param bundle
	 * 		Containing bundle.
	 * @param directoryName
	 * 		Name of directory to delete.
	 */
	public void deleteDirectory(@Nonnull Workspace workspace,
								@Nonnull WorkspaceResource resource,
								@Nonnull FileBundle bundle,
								@Nonnull String directoryName) {
		// TODO: Ask user if they are sure
		//  - Use config to check if "are you sure" prompts should be bypassed
		boolean isRootDirectory = directoryName.isEmpty();
		String directoryNamePrefix = directoryName + "/";
		for (FileInfo value : bundle.valuesAsCopy()) {
			String path = value.getName();
			if (isRootDirectory) {
				// Source is in the root directory, and the current file is also in the root directory.
				if (path.indexOf('/') == -1) {
					bundle.remove(path);
				}
			} else {
				// Source is in a directory, and the current file is in the same directory.
				if (path.startsWith(directoryNamePrefix)) {
					bundle.remove(path);
				}
			}
		}
	}

	/**
	 * Prompts the user to select fields within the class to remove.
	 *
	 * @param workspace
	 * 		Containing workspace.
	 * @param resource
	 * 		Containing resource.
	 * @param bundle
	 * 		Containing bundle.
	 * @param info
	 * 		Class to update.
	 */
	public void deleteClassFields(@Nonnull Workspace workspace,
								  @Nonnull WorkspaceResource resource,
								  @Nonnull JvmClassBundle bundle,
								  @Nonnull JvmClassInfo info) {
		ItemListSelectionPopup.forFields(info, fields -> deleteClassFields(workspace, resource, bundle, info, fields))
				.withMultipleSelection()
				.withTitle(Lang.getBinding("menu.edit.remove.field"))
				.withTextMapping(field -> textService.getFieldMemberTextProvider(workspace, resource, bundle, info, field).makeText())
				.withGraphicMapping(field -> iconService.getClassMemberIconProvider(workspace, resource, bundle, info, field).makeIcon())
				.show();
	}

	/**
	 * Removes the given fields from the given class.
	 *
	 * @param workspace
	 * 		Containing workspace.
	 * @param resource
	 * 		Containing resource.
	 * @param bundle
	 * 		Containing bundle.
	 * @param declaringClass
	 * 		Class declaring the methods.
	 * @param fields
	 * 		Fields to delete.
	 */
	public void deleteClassFields(@Nonnull Workspace workspace,
								  @Nonnull WorkspaceResource resource,
								  @Nonnull JvmClassBundle bundle,
								  @Nonnull JvmClassInfo declaringClass,
								  @Nonnull Collection<FieldMember> fields) {
		ClassWriter writer = new ClassWriter(0);
		MemberRemovingVisitor visitor = new MemberRemovingVisitor(writer, FieldPredicate.of(fields));
		declaringClass.getClassReader().accept(visitor, 0);
		bundle.put(declaringClass.toJvmClassBuilder()
				.adaptFrom(new ClassReader(writer.toByteArray()))
				.build());
	}

	/**
	 * Prompts the user to select methods within the class to remove.
	 *
	 * @param workspace
	 * 		Containing workspace.
	 * @param resource
	 * 		Containing resource.
	 * @param bundle
	 * 		Containing bundle.
	 * @param info
	 * 		Class to update.
	 */
	public void deleteClassMethods(@Nonnull Workspace workspace,
								   @Nonnull WorkspaceResource resource,
								   @Nonnull JvmClassBundle bundle,
								   @Nonnull JvmClassInfo info) {
		ItemListSelectionPopup.forMethods(info, methods -> deleteClassMethods(workspace, resource, bundle, info, methods))
				.withMultipleSelection()
				.withTitle(Lang.getBinding("menu.edit.remove.method"))
				.withTextMapping(method -> textService.getMethodMemberTextProvider(workspace, resource, bundle, info, method).makeText())
				.withGraphicMapping(method -> iconService.getClassMemberIconProvider(workspace, resource, bundle, info, method).makeIcon())
				.show();
	}

	/**
	 * Removes the given methods from the given class.
	 *
	 * @param workspace
	 * 		Containing workspace.
	 * @param resource
	 * 		Containing resource.
	 * @param bundle
	 * 		Containing bundle.
	 * @param declaringClass
	 * 		Class declaring the methods.
	 * @param methods
	 * 		Methods to delete.
	 */
	public void deleteClassMethods(@Nonnull Workspace workspace,
								   @Nonnull WorkspaceResource resource,
								   @Nonnull JvmClassBundle bundle,
								   @Nonnull JvmClassInfo declaringClass,
								   @Nonnull Collection<MethodMember> methods) {
		ClassWriter writer = new ClassWriter(0);
		MemberRemovingVisitor visitor = new MemberRemovingVisitor(writer, MethodPredicate.of(methods));
		declaringClass.getClassReader().accept(visitor, 0);
		bundle.put(declaringClass.toJvmClassBuilder()
				.adaptFrom(new ClassReader(writer.toByteArray()))
				.build());
	}

	/**
	 * Prompts the user to select annotations on the class to remove.
	 *
	 * @param workspace
	 * 		Containing workspace.
	 * @param resource
	 * 		Containing resource.
	 * @param bundle
	 * 		Containing bundle.
	 * @param info
	 * 		Class to update.
	 */
	public void deleteClassAnnotations(@Nonnull Workspace workspace,
									   @Nonnull WorkspaceResource resource,
									   @Nonnull JvmClassBundle bundle,
									   @Nonnull JvmClassInfo info) {
		ItemListSelectionPopup.forAnnotationRemoval(info, annotations -> {
					List<String> names = annotations.stream()
							.map(AnnotationInfo::getDescriptor)
							.map(desc -> desc.substring(1, desc.length() - 1))
							.collect(Collectors.toList());
					ClassWriter writer = new ClassWriter(0);
					ClassAnnotationRemovingVisitor visitor = new ClassAnnotationRemovingVisitor(writer, names);
					info.getClassReader().accept(visitor, 0);
					bundle.put(info.toJvmClassBuilder()
							.adaptFrom(new ClassReader(writer.toByteArray()))
							.build());
				})
				.withMultipleSelection()
				.withTitle(Lang.getBinding("menu.edit.remove.annotation"))
				.withTextMapping(anno -> textService.getAnnotationTextProvider(workspace, resource, bundle, info, anno).makeText())
				.withGraphicMapping(anno -> iconService.getAnnotationIconProvider(workspace, resource, bundle, info, anno).makeIcon())
				.show();
	}

	/**
	 * Makes the given methods no-op.
	 *
	 * @param workspace
	 * 		Containing workspace.
	 * @param resource
	 * 		Containing resource.
	 * @param bundle
	 * 		Containing bundle.
	 * @param declaringClass
	 * 		Class declaring the methods.
	 * @param methods
	 * 		Methods to noop.
	 */
	public void makeMethodsNoop(@Nonnull Workspace workspace,
								@Nonnull WorkspaceResource resource,
								@Nonnull JvmClassBundle bundle,
								@Nonnull JvmClassInfo declaringClass,
								@Nonnull Collection<MethodMember> methods) {
		ClassWriter writer = new ClassWriter(0);
		MethodNoopingVisitor visitor = new MethodNoopingVisitor(writer, MethodPredicate.of(methods));
		declaringClass.getClassReader().accept(visitor, 0);
		bundle.put(declaringClass.toJvmClassBuilder()
				.adaptFrom(new ClassReader(writer.toByteArray()))
				.build());
	}

	/**
	 * Looks for the {@link Navigable} component representing the path and returns it if found.
	 * If no such component exists, it should be generated by the passed supplier, which then gets returned.
	 * <br>
	 * The tab containing the {@link Navigable} component is selected when returned.
	 *
	 * @param path
	 * 		Path to navigate to.
	 * @param factory
	 * 		Factory to create a tab for displaying content located at the given path,
	 * 		should a tab for the content not already exist.
	 * 		<br>
	 * 		<b>NOTE:</b> It is required/assumed that the {@link Tab#getContent()} is a
	 * 		component implementing {@link Navigable}.
	 *
	 * @return Navigable content representing content of the path.
	 */
	@Nonnull
	public Navigable getOrCreatePathContent(@Nonnull PathNode<?> path, @Nonnull Supplier<DockingTab> factory) {
		List<Navigable> children = navigationManager.getNavigableChildrenByPath(path);
		if (children.isEmpty()) {
			return createContent(factory);
		} else {
			// Content by path is already open.
			Navigable navigable = children.get(0);
			selectTab(navigable);
			navigable.requestFocus();
			return navigable;
		}
	}

	@Nonnull
	private static Navigable createContent(@Nonnull Supplier<DockingTab> factory) {
		// Create the tab for the content, then display it.
		DockingTab tab = factory.get();
		tab.select();
		return (Navigable) tab.getContent();
	}

	private static void setupFileTabContextMenu(@Nonnull FileInfo info, DockingTab tab) {
		ContextMenu menu = new ContextMenu();
		ObservableList<MenuItem> items = menu.getItems();
		items.add(action("menu.tab.copypath", CarbonIcons.COPY_LINK, () -> ClipboardUtil.copyString(info)));
		items.add(separator());
		addCloseActions(menu, tab);
		tab.setContextMenu(menu);
	}

	/**
	 * Selects the containing {@link DockingTab} that contains the content.
	 *
	 * @param navigable
	 * 		Navigable content to select in its containing {@link DockingRegion}.
	 */
	private static void selectTab(Navigable navigable) {
		if (navigable instanceof Node node) {
			while (node != null) {
				// Get the parent of the node, skip the intermediate 'content area' from tab-pane default skin.
				Parent parent = node.getParent();
				if (parent.getStyleClass().contains("tab-content-area"))
					parent = parent.getParent();

				// If the tab content is the node, select it and return.
				if (parent instanceof DockingRegion tabParent)
					for (DockingTab tab : tabParent.getDockTabs())
						if (tab.getContent() == node) {
							tab.select();
							return;
						}

				// Next parent.
				node = parent;
			}
		}
	}

	/**
	 * Shorthand for tab-creation + graphic setting.
	 *
	 * @param region
	 * 		Parent region to spawn in.
	 * @param title
	 * 		Tab title.
	 * @param graphic
	 * 		Tab graphic.
	 * @param content
	 * 		Tab content.
	 *
	 * @return Created tab.
	 */
	private static DockingTab createTab(@Nonnull DockingRegion region,
										@Nonnull String title,
										@Nonnull Node graphic,
										@Nonnull Node content) {
		DockingTab tab = region.createTab(title, content);
		tab.setGraphic(graphic);
		return tab;
	}

	/**
	 * Adds close actions to the given menu.
	 * <ul>
	 *     <li>Close</li>
	 *     <li>Close others</li>
	 *     <li>Close all</li>
	 * </ul>
	 *
	 * @param menu
	 * 		Menu to add to.
	 * @param currentTab
	 * 		Current tab reference.
	 */
	private static void addCloseActions(@Nonnull ContextMenu menu, @Nonnull DockingTab currentTab) {
		menu.getItems().addAll(
				action("menu.tab.close", CarbonIcons.CLOSE, currentTab::close),
				action("menu.tab.closeothers", CarbonIcons.CLOSE, () -> {
					for (DockingTab regionTab : currentTab.getRegion().getDockTabs()) {
						if (regionTab != currentTab)
							regionTab.close();
					}
				}),
				action("menu.tab.closeall", CarbonIcons.CLOSE, () -> {
					for (DockingTab regionTab : currentTab.getRegion().getDockTabs())
						regionTab.close();
				})
		);
	}

	/**
	 * @param workspace
	 * 		Containing workspace.
	 * @param resource
	 * 		Containing resource.
	 * @param bundle
	 * 		Containing bundle.
	 * @param info
	 * 		Class item to end path with.
	 *
	 * @return Class path node.
	 */
	@Nonnull
	private static ClassPathNode buildPath(@Nonnull Workspace workspace,
										   @Nonnull WorkspaceResource resource,
										   @Nonnull ClassBundle<?> bundle,
										   @Nonnull ClassInfo info) {
		return PathNodes.classPath(workspace, resource, bundle, info);
	}

	/**
	 * @param workspace
	 * 		Containing workspace.
	 * @param resource
	 * 		Containing resource.
	 * @param bundle
	 * 		Containing bundle.
	 * @param info
	 * 		File item to end path with.
	 *
	 * @return File path node.
	 */
	@Nonnull
	private static FilePathNode buildPath(@Nonnull Workspace workspace,
										  @Nonnull WorkspaceResource resource,
										  @Nonnull FileBundle bundle,
										  @Nonnull FileInfo info) {
		return PathNodes.filePath(workspace, resource, bundle, info);
	}

	@Nonnull
	@Override
	public String getServiceId() {
		return ID;
	}

	@Nonnull
	@Override
	public ActionsConfig getServiceConfig() {
		return config;
	}
}
