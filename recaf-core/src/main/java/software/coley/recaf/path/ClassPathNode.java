package software.coley.recaf.path;

import jakarta.annotation.Nonnull;
import jakarta.annotation.Nullable;
import software.coley.recaf.info.ClassInfo;
import software.coley.recaf.info.InnerClassInfo;
import software.coley.recaf.info.annotation.AnnotationInfo;
import software.coley.recaf.info.member.ClassMember;

import java.util.Set;

/**
 * Path node for {@link ClassInfo} types.
 *
 * @author Matt Coley
 */
public class ClassPathNode extends AbstractPathNode<String, ClassInfo> {
	/**
	 * Type identifier for class nodes.
	 */
	public static final String TYPE_ID = "class";

	/**
	 * Node without parent.
	 *
	 * @param info
	 * 		Class value.
	 */
	public ClassPathNode(@Nonnull ClassInfo info) {
		this(null, info);
	}

	/**
	 * Node with parent.
	 *
	 * @param parent
	 * 		Parent node.
	 * @param info
	 * 		Class value.
	 *
	 * @see DirectoryPathNode#child(ClassInfo)
	 */
	public ClassPathNode(@Nullable DirectoryPathNode parent, @Nonnull ClassInfo info) {
		super(TYPE_ID, parent, ClassInfo.class, info);
	}

	/**
	 * @param member
	 * 		Member to wrap into node.
	 *
	 * @return Path node of member, with the current class as parent.
	 */
	@Nonnull
	public ClassMemberPathNode child(@Nonnull ClassMember member) {
		return new ClassMemberPathNode(this, member);
	}

	/**
	 * @param innerClass
	 * 		Inner class to wrap into node.
	 *
	 * @return Path node of inner class, with the current class as parent.
	 */
	@Nonnull
	public InnerClassPathNode child(@Nonnull InnerClassInfo innerClass) {
		return new InnerClassPathNode(this, innerClass);
	}

	/**
	 * @param annotation
	 * 		Annotation to wrap into node.
	 *
	 * @return Path node of annotation, with the current member as parent.
	 */
	@Nonnull
	public AnnotationPathNode child(@Nonnull AnnotationInfo annotation) {
		return new AnnotationPathNode(this, annotation);
	}

	@Override
	public DirectoryPathNode getParent() {
		return (DirectoryPathNode) super.getParent();
	}

	@Nonnull
	@Override
	public Set<String> directParentTypeIds() {
		return Set.of(DirectoryPathNode.TYPE_ID);
	}

	@Override
	public int localCompare(PathNode<?> o) {
		if (o instanceof ClassPathNode classPathNode) {
			String name = getValue().getName();
			String otherName = classPathNode.getValue().getName();
			return String.CASE_INSENSITIVE_ORDER.compare(name, otherName);
		}
		return 0;
	}
}
