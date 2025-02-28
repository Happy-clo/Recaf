package software.coley.recaf.services.mapping.gen;

import jakarta.annotation.Nonnull;
import software.coley.recaf.config.ConfigContainer;
import software.coley.recaf.config.ConfigGroups;

/**
 * Provider for a {@link NameGenerator} implementation.
 * Each provider can be configured as a {@link ConfigContainer}.
 *
 * @param <T>
 * 		Name generator implementation type.
 *
 * @author Matt Coley
 */
public interface NameGeneratorProvider<T extends NameGenerator> extends ConfigContainer {
	/**
	 * Group ID for {@link ConfigContainer#getGroup()}.
	 */
	String GROUP_ID = ConfigGroups.SERVICE_MAPPING + ConfigGroups.PACKAGE_SPLIT + "name-gen-provider";

	/**
	 * @return New instance of {@link NameGenerator} implementation.
	 */
	@Nonnull
	T createGenerator();
}
