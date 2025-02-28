package software.coley.recaf.services.decompile;

import jakarta.annotation.Nonnull;
import software.coley.recaf.info.properties.builtin.CachedDecompileProperty;

/**
 * Common decompiler operations.
 *
 * @author Matt Coley
 * @see JvmDecompiler For decompiling JVM bytecode.
 * @see AndroidDecompiler For decompiling Android/Dalvik bytecode.
 * @see DecompilerConfig For config management of decompiler values,
 * and ensuring {@link CachedDecompileProperty} values are compatible with current settings.
 */
public interface Decompiler {
	/**
	 * @return Decompiler name.
	 */
	@Nonnull
	String getName();

	/**
	 * @return Decompiler version.
	 */
	@Nonnull
	String getVersion();

	/**
	 * @return Decompiler config.
	 */
	@Nonnull
	DecompilerConfig getConfig();
}
