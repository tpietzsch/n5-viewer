package org.janelia.saalfeldlab.n5.metadata;

import java.util.Arrays;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.function.Predicate;
import java.util.regex.Pattern;

import org.janelia.saalfeldlab.n5.N5Reader;
import org.janelia.saalfeldlab.n5.N5TreeNode;

public class N5ViewerMultichannelMetadata implements N5MetadataGroup<MultiscaleMetadata<?>> {

	public static final Predicate<String> channelPredicate = Pattern.compile("^c\\d+$").asPredicate();

	private final String basePath;

	private final MultiscaleMetadata<?>[] childMetadata;

	public N5ViewerMultichannelMetadata(final String basePath, final MultiscaleMetadata<?>[] childMetadata) {
		this.basePath = basePath;
		this.childMetadata = childMetadata;
	}

	@Override
	public String getPath() {
		return basePath;
	}

	@Override
	public String[] getPaths() {

		return Arrays.stream(childMetadata).map(m -> m.getPath()).toArray(String[]::new);
	}

	@Override
	public MultiscaleMetadata<?>[] getChildrenMetadata() {

		return childMetadata;
	}

	public static class N5ViewerMultichannelMetadataParser implements N5MetadataParser<N5ViewerMultichannelMetadata> {

		@Override
		public Optional<N5ViewerMultichannelMetadata> parseMetadata(N5Reader n5, N5TreeNode node) {

			final Map<String, N5TreeNode> scaleLevelNodes = new HashMap<>();
			for (final N5TreeNode childNode : node.childrenList()) {
				// note, the n5v spec is such that
				// channels are always parents of scales :
				// e.g. a path of c0/s0
				// this is why I check that there is a MultiscaleMetadata instance
				if (channelPredicate.test(childNode.getNodeName()) && childNode.getMetadata() instanceof MultiscaleMetadata )
					scaleLevelNodes.put(childNode.getNodeName(), childNode);
			}

			if (scaleLevelNodes.isEmpty())
				return Optional.empty();

			final MultiscaleMetadata[] childMetadata = scaleLevelNodes.values().stream().map(N5TreeNode::getMetadata).toArray(MultiscaleMetadata[]::new);
			return Optional.of(new N5ViewerMultichannelMetadata(node.getPath(), childMetadata));
		}
	}

}
