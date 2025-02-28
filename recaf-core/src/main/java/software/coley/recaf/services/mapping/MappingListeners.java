package software.coley.recaf.services.mapping;

import jakarta.annotation.Nonnull;
import jakarta.annotation.Nullable;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import software.coley.recaf.services.Service;
import software.coley.recaf.workspace.model.bundle.JvmClassBundle;
import software.coley.recaf.workspace.model.resource.WorkspaceResource;

import java.util.ArrayList;
import java.util.List;

/**
 * Manages listeners for things like {@link MappingResults} application in an application-scoped, as opposed to
 * some of the other mapping services which are workspace-scoped.
 *
 * @author Matt Coley
 */
@ApplicationScoped
public class MappingListeners implements Service {
	public static final String SERVICE_ID = "mapping-listeners";
	private final List<MappingApplicationListener> mappingApplicationListeners = new ArrayList<>();
	private final MappingListenersConfig config;

	@Inject
	public MappingListeners(@Nonnull MappingListenersConfig config) {
		this.config = config;
	}

	/**
	 * Adds a listener which is passed to created {@link MappingResults} from
	 * {@link MappingApplier#applyToPrimaryResource(Mappings)} and
	 * {@link MappingApplier#applyToClasses(Mappings, WorkspaceResource, JvmClassBundle, List)}.
	 * <p>
	 * This allows you to listen to all mapping operations done via proper API usage, intercepting before they
	 * execute the task, and after they complete the mapping task.
	 *
	 * @param listener
	 * 		Listener to add.
	 */
	public void addMappingApplicationListener(@Nonnull MappingApplicationListener listener) {
		mappingApplicationListeners.add(listener);
	}

	/**
	 * @param listener
	 * 		Listener to remove.
	 *
	 * @return {@code true} when item was removed.
	 * {@code false} when item was not in the list to begin with.
	 */
	public boolean removeMappingApplicationListener(@Nonnull MappingApplicationListener listener) {
		return mappingApplicationListeners.remove(listener);
	}

	/**
	 * @return Application listener encompassing all the current items in {@link #mappingApplicationListeners},
	 * or {@code null} if there are no listeners.
	 */
	@Nullable
	public MappingApplicationListener createBundledMappingApplicationListener() {
		final List<MappingApplicationListener> listeners = mappingApplicationListeners;

		// Simple edge cases.
		if (listeners.isEmpty())
			return null;
		else if (listeners.size() == 1)
			return listeners.get(0);

		// Bundle multiple listeners.
		return new MappingApplicationListener() {
			@Override
			public void onPreApply(@Nonnull MappingResults mappingResults) {
				for (MappingApplicationListener listener : listeners) {
					listener.onPreApply(mappingResults);
				}
			}

			@Override
			public void onPostApply(@Nonnull MappingResults mappingResults) {
				for (MappingApplicationListener listener : listeners) {
					listener.onPostApply(mappingResults);
				}
			}
		};
	}

	@Nonnull
	@Override
	public String getServiceId() {
		return SERVICE_ID;
	}

	@Nonnull
	@Override
	public MappingListenersConfig getServiceConfig() {
		return config;
	}
}
