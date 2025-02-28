package software.coley.recaf.services.mapping.gen.filter;

import jakarta.annotation.Nonnull;
import jakarta.annotation.Nullable;
import software.coley.recaf.info.ClassInfo;
import software.coley.recaf.info.member.FieldMember;
import software.coley.recaf.info.member.MethodMember;
import software.coley.recaf.services.mapping.gen.NameGeneratorFilter;
import software.coley.recaf.util.TextMatchMode;

/**
 * Filter that includes classes, fields, and methods by their names.
 *
 * @author Matt Coley
 * @see ExcludeNameFilter
 */
public class IncludeNameFilter extends NameGeneratorFilter {
	private final String name;
	private final TextMatchMode matchMode;
	private final boolean targetClasses;
	private final boolean targetFields;
	private final boolean targetMethods;

	/**
	 * @param next
	 * 		Next filter to link. Chaining filters allows for {@code thisFilter && nextFilter}.
	 * @param name
	 * 		Name pattern to exclude.
	 * @param matchMode
	 * 		Text match mode.
	 * @param targetClasses
	 * 		Check against class names.
	 * @param targetFields
	 * 		Check against field names.
	 * @param targetMethods
	 * 		Check against methods names.
	 */
	public IncludeNameFilter(@Nullable NameGeneratorFilter next,
							 @Nonnull String name, @Nonnull TextMatchMode matchMode,
							 boolean targetClasses, boolean targetFields, boolean targetMethods) {
		super(next, false);
		this.name = name;
		this.matchMode = matchMode;
		this.targetClasses = targetClasses;
		this.targetFields = targetFields;
		this.targetMethods = targetMethods;
	}

	@Override
	public boolean shouldMapClass(@Nonnull ClassInfo info) {
		if (targetClasses && matchMode.match(this.name, info.getName()))
			return true;
		return super.shouldMapClass(info);
	}

	@Override
	public boolean shouldMapField(@Nonnull ClassInfo owner, @Nonnull FieldMember field) {
		if (targetFields && matchMode.match(this.name, field.getName()))
			return true;
		return super.shouldMapField(owner, field);
	}

	@Override
	public boolean shouldMapMethod(@Nonnull ClassInfo owner, @Nonnull MethodMember method) {
		if (targetMethods && matchMode.match(this.name, method.getName()))
			return true;
		return super.shouldMapMethod(owner, method);
	}
}
