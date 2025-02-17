/**
 * License: GPL
 *
 * This program is free software; you can redistribute it and/or
 * modify it under the terms of the GNU General Public License 2
 * as published by the Free Software Foundation.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program; if not, write to the Free Software
 * Foundation, Inc., 59 Temple Place - Suite 330, Boston, MA  02111-1307, USA.
 */
package org.janelia.saalfeldlab.n5.bdv;

import bdv.BigDataViewer;
import bdv.tools.InitializeViewerState;
import bdv.tools.brightness.ConverterSetup;
import bdv.tools.brightness.RealARGBColorConverterSetup;
import bdv.tools.transformation.TransformedSource;
import bdv.ui.splitpanel.SplitPanel;
import bdv.util.*;
import bdv.util.volatiles.SharedQueue;
import bdv.viewer.Source;
import bdv.viewer.SourceAndConverter;
import bdv.viewer.ViewerPanel;
import net.imglib2.RandomAccessibleInterval;
import net.imglib2.Volatile;
import net.imglib2.cache.img.CachedCellImg;
import net.imglib2.converter.Converter;
import net.imglib2.display.RealARGBColorConverter;
import net.imglib2.display.ScaledARGBConverter;
import net.imglib2.realtransform.AffineTransform3D;
import net.imglib2.type.NativeType;
import net.imglib2.type.numeric.ARGBType;
import net.imglib2.type.numeric.NumericType;
import net.imglib2.type.numeric.RealType;
import net.imglib2.type.volatiles.VolatileARGBType;
import net.imglib2.util.Util;
import net.imglib2.view.Views;
import org.janelia.saalfeldlab.n5.N5Reader;
import org.janelia.saalfeldlab.n5.imglib2.N5Utils;
import org.janelia.saalfeldlab.n5.metadata.*;
import org.janelia.saalfeldlab.n5.metadata.canonical.CanonicalMultichannelMetadata;
import org.janelia.saalfeldlab.n5.metadata.canonical.CanonicalMultiscaleMetadata;
import org.janelia.saalfeldlab.n5.metadata.canonical.CanonicalSpatialMetadata;
import org.janelia.saalfeldlab.n5.ui.DataSelection;
import org.scijava.ui.behaviour.io.InputTriggerConfig;
import org.scijava.ui.behaviour.util.TriggerBehaviourBindings;

import java.awt.*;
import java.awt.event.ComponentAdapter;
import java.awt.event.ComponentEvent;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;


/**
 * {@link BigDataViewer}-based application for viewing N5 datasets.
 *
 * @author Igor Pisarev
 * @author John Bogovic
 */
public class N5Viewer {

	private int numTimepoints = 1;

	private boolean is2D = true;

	private final SharedQueue sharedQueue;

	private final BdvHandle bdv;


	public BdvHandle getBdv() {
		return bdv;
	}

	public SplitPanel getBdvSplitPanel() {
		return bdv.getSplitPanel();
	}

	public N5Viewer(final Frame parent, final DataSelection selection) throws IOException {
		this(parent, selection, true);
	}

	/**
	 * Creates a new N5Viewer with the given data sets.
	 * @param parentFrame parent frame, can be null
	 * @param dataSelection data sets to display
	 * @param wantFrame if true, use BdvHandleFrame and display a window. If false, use a BdvHandlePanel and do not display anything.
	 * @throws IOException
	 */
	public < T extends NumericType< T > & NativeType< T >,
					V extends Volatile< T > & NumericType< V >,
					R extends N5Reader >
			N5Viewer(final Frame parentFrame,
					 final DataSelection dataSelection,
					 final boolean wantFrame ) throws IOException
	{
		Prefs.showScaleBar( true );

		this.sharedQueue = new SharedQueue( Math.max( 1, Runtime.getRuntime().availableProcessors() / 2 ) );

		// TODO: These setups are not used anymore, because BdvFunctions creates its own.
		//       They either need to be deleted from here or integrated somehow.
		final List< ConverterSetup > converterSetups = new ArrayList<>();

		final List< SourceAndConverter< ? > > sourcesAndConverters = new ArrayList<>();

		final List<N5Metadata> selected = new ArrayList<>();
		for( N5Metadata meta : dataSelection.metadata )
		{
			if( meta instanceof N5ViewerMultichannelMetadata )
			{
				N5ViewerMultichannelMetadata mc = (N5ViewerMultichannelMetadata)meta;
				for( MultiscaleMetadata<?> m : mc.getChildrenMetadata() )
					selected.add( m );
			}
			else if ( meta instanceof CanonicalMultichannelMetadata )
			{
				CanonicalMultichannelMetadata mc = (CanonicalMultichannelMetadata)meta;
				for( N5Metadata m : mc.getChildrenMetadata() )
					selected.add( m );
			}
			else
				selected.add( meta );
		}

		final List<N5Source<T>> sources = new ArrayList<>();
		final List<N5VolatileSource<T, V>> volatileSources = new ArrayList<>();

		buildN5Sources(dataSelection.n5, selected, sharedQueue, converterSetups, sourcesAndConverters, sources, volatileSources);

		BdvHandle bdvHandle = null;

		BdvOptions options = BdvOptions.options().frameTitle("N5 Viewer");
		if (is2D) {
			options = options.is2D();
		}

		for (SourceAndConverter<?> sourcesAndConverter : sourcesAndConverters) {
			if (bdvHandle == null) {
				if (wantFrame) {
					// Create and show a BdvHandleFrame with the first source
					bdvHandle = BdvFunctions.show(sourcesAndConverter, BdvOptions.options()).getBdvHandle();
				}
				else {
					// Create a BdvHandlePanel, but don't show it
					bdvHandle = new BdvHandlePanel(parentFrame, options);
					// Add the first source to it
					BdvFunctions.show(sourcesAndConverter, BdvOptions.options().addTo(bdvHandle));
				}
			}
			else {
				// Subsequent sources are added to the existing handle
				BdvFunctions.show(sourcesAndConverter, BdvOptions.options().addTo(bdvHandle));
			}
		}
		this.bdv = bdvHandle;

		if (bdv != null) {
			ViewerPanel viewerPanel = bdv.getViewerPanel();
			if (viewerPanel != null) {
				viewerPanel.setNumTimepoints(numTimepoints);
				initCropController( sources );
				// Delay initTransform until the viewer is shown because it needs to have a size.
				viewerPanel.addComponentListener(new ComponentAdapter() {
					boolean needsInit = true;
					@Override
					public void componentShown(ComponentEvent e) {
						if (needsInit) {
							InitializeViewerState.initTransform(viewerPanel);
							needsInit = false;
						}
					}
				});
			}
		}
	}

	public < T extends NumericType< T > & NativeType< T >,
				V extends Volatile< T > & NumericType< V >,
				R extends N5Reader >
			void addData( final DataSelection selection ) throws IOException
	{
		final ArrayList< ConverterSetup > converterSetups = new ArrayList<>();
		final ArrayList< SourceAndConverter< ? > > sourcesAndConverters = new ArrayList<>();

		final List<N5Metadata> selected = new ArrayList<>();
		for( N5Metadata meta : selection.metadata )
		{
			if( meta instanceof N5ViewerMultichannelMetadata )
			{
				N5ViewerMultichannelMetadata mc = (N5ViewerMultichannelMetadata)meta;
				selected.addAll(Arrays.asList(mc.getChildrenMetadata()));
			}
			else if ( meta instanceof CanonicalMultichannelMetadata )
			{
				CanonicalMultichannelMetadata mc = (CanonicalMultichannelMetadata)meta;
				selected.addAll(Arrays.asList(mc.getChildrenMetadata()));
			}
			else
				selected.add( meta );
		}

		final List<N5Source<T>> sources = new ArrayList<>();
		final List<N5VolatileSource<T, V>> volatileSources = new ArrayList<>();

		buildN5Sources(selection.n5, selected, sharedQueue, converterSetups, sourcesAndConverters, sources, volatileSources);


		for (SourceAndConverter<?> sourcesAndConverter : sourcesAndConverters) {
			BdvFunctions.show(sourcesAndConverter, BdvOptions.options().addTo(bdv));
		}
	}

	public < T extends NumericType< T > & NativeType< T >,
					V extends Volatile< T > & NumericType< V >> void buildN5Sources(
		final N5Reader n5,
		final List<N5Metadata> selectedMetadata,
		final SharedQueue sharedQueue,
		final List< ConverterSetup > converterSetups,
		final List< SourceAndConverter< ? > > sourcesAndConverters,
		final List<N5Source<T>> sources,
		final List<N5VolatileSource<T, V>> volatileSources) throws IOException
	{
		final ArrayList<MetadataSource<?>> additionalSources = new ArrayList<>();

		int i;
		for ( i = 0; i < selectedMetadata.size(); ++i )
		{
			String[] datasetsToOpen = null;
			AffineTransform3D[] transforms = null;

			final N5Metadata metadata = selectedMetadata.get( i );
			if (metadata instanceof N5SingleScaleMetadata) {
				final N5SingleScaleMetadata singleScaleDataset = (N5SingleScaleMetadata) metadata;
				String[] tmpDatasets= new String[]{ singleScaleDataset.getPath() };
				AffineTransform3D[] tmpTransforms = new AffineTransform3D[]{ singleScaleDataset.spatialTransform3d() };

				MultiscaleDatasets msd = MultiscaleDatasets.sort( tmpDatasets, tmpTransforms );
				datasetsToOpen = msd.getPaths();
				transforms = msd.getTransforms();
			} else if (metadata instanceof N5MultiScaleMetadata) {
				final N5MultiScaleMetadata multiScaleDataset = (N5MultiScaleMetadata) metadata;
				datasetsToOpen = multiScaleDataset.getPaths();
				transforms = multiScaleDataset.spatialTransforms3d();
			} else if (metadata instanceof N5CosemMetadata ) {
				final N5CosemMetadata singleScaleCosemDataset = (N5CosemMetadata) metadata;
				datasetsToOpen = new String[]{ singleScaleCosemDataset.getPath() };
				transforms = new AffineTransform3D[]{ singleScaleCosemDataset.spatialTransform3d() };
			} else if (metadata instanceof CanonicalSpatialMetadata ) {
				final CanonicalSpatialMetadata canonicalDataset = (CanonicalSpatialMetadata) metadata;
				datasetsToOpen = new String[]{ canonicalDataset.getPath() };
				transforms = new AffineTransform3D[]{ canonicalDataset.getSpatialTransform().spatialTransform3d() };
			} else if (metadata instanceof N5CosemMultiScaleMetadata ) {
				final N5CosemMultiScaleMetadata multiScaleDataset = (N5CosemMultiScaleMetadata) metadata;
				MultiscaleDatasets msd = MultiscaleDatasets.sort( multiScaleDataset.getPaths(), multiScaleDataset.spatialTransforms3d() );
				datasetsToOpen = msd.getPaths();
				transforms = msd.getTransforms();
			} else if (metadata instanceof CanonicalMultiscaleMetadata ) {
				final CanonicalMultiscaleMetadata multiScaleDataset = (CanonicalMultiscaleMetadata) metadata;
				MultiscaleDatasets msd = MultiscaleDatasets.sort( multiScaleDataset.getPaths(), multiScaleDataset.spatialTransforms3d() );
				datasetsToOpen = msd.getPaths();
				transforms = msd.getTransforms();
			}
			else if( metadata instanceof N5DatasetMetadata ) {
				final List<MetadataSource<?>> addTheseSources = MetadataSource.buildMetadataSources(n5, (N5DatasetMetadata)metadata);
				if( addTheseSources != null )
					additionalSources.addAll(addTheseSources);
			}
			else {
				datasetsToOpen = new String[]{ metadata.getPath() };
				transforms = new AffineTransform3D[] { new AffineTransform3D() };
			}

			if( datasetsToOpen == null || datasetsToOpen.length == 0 )
				continue;

			// is2D should be true at the end of this loop if all sources are 2D
			is2D = true;

			@SuppressWarnings( "rawtypes" )
			final RandomAccessibleInterval[] images = new RandomAccessibleInterval[datasetsToOpen.length];
			for ( int s = 0; s < images.length; ++s )
			{
				CachedCellImg<?, ?> vimg = N5Utils.openVolatile( n5, datasetsToOpen[s] );
				if( vimg.numDimensions() == 2 )
				{
					images[ s ] = Views.addDimension(vimg, 0, 0);
					is2D = is2D && true;
				}
				else
				{
					images[ s ] = vimg;
					is2D = is2D && false;
				}
			}

			@SuppressWarnings( "unchecked" )
			final N5Source<T> source = new N5Source<>(
					(T) Util.getTypeFromInterval(images[0]),
					"source " + (i + 1),
					images,
					transforms);

			final N5VolatileSource<T, V> volatileSource = source.asVolatile(sharedQueue);

			sources.add(source);
			volatileSources.add(volatileSource);

			addSourceToListsGenericType( volatileSource, i + 1, numTimepoints, volatileSource.getType(), converterSetups, sourcesAndConverters );
		}

		for( MetadataSource src : additionalSources ) {
			if( src.numTimePoints() > numTimepoints )
				numTimepoints = src.numTimePoints();

			addSourceToListsGenericType( src, i + 1, src.numTimePoints(), src.getType(), converterSetups, sourcesAndConverters );
		}
	}

	private < T extends NumericType< T > & NativeType< T > > void initCropController( final List< ? extends Source< T > > sources )
	{
		final TriggerBehaviourBindings bindings = bdv.getBdvHandle().getTriggerbindings();
		final InputTriggerConfig config = new InputTriggerConfig();

		final CropController< T > cropController = new CropController<>(
					bdv.getViewerPanel(),
					sources,
					config,
					bdv.getKeybindings(),
					config );

		bindings.addBehaviourMap( "crop", cropController.getBehaviourMap() );
		bindings.addInputTriggerMap( "crop", cropController.getInputTriggerMap() );
	}

	/**
	 * Add the given {@code source} to the lists of {@code converterSetups}
	 * (using specified {@code setupId}) and {@code sources}. For this, the
	 * {@code source} is wrapped with an appropriate {@link Converter} to
	 * {@link ARGBType} and into a {@link TransformedSource}.
	 *
	 * @param source
	 *            source to add.
	 * @param setupId
	 *            id of the new source for use in {@link bdv.tools.brightness.SetupAssignments}.
	 * @param numTimepoints
	 *            the number of timepoints of the source.
	 * @param type
	 *            instance of the {@code img} type.
	 * @param converterSetups
	 *            list of {@link ConverterSetup}s to which the source should be
	 *            added.
	 * @param sources
	 *            list of {@link SourceAndConverter}s to which the source should
	 *            be added.
	 */
	@SuppressWarnings( { "rawtypes", "unchecked" } )
	private static < T > void addSourceToListsGenericType(
			final Source source,
			final int setupId,
			final int numTimepoints,
			final T type,
			final List< ConverterSetup > converterSetups,
			final List< SourceAndConverter< ? > > sources )
	{
		if ( type instanceof RealType ) {
			addSourceToListsRealType(source, setupId, converterSetups, ( List ) sources );
		} else if ( type instanceof ARGBType )
			addSourceToListsARGBType(source, setupId, converterSetups, ( List ) sources );
		else if ( type instanceof VolatileARGBType )
			addSourceToListsVolatileARGBType(source, setupId, converterSetups, ( List ) sources );
		else
			throw new IllegalArgumentException( "Unknown source type. Expected RealType, ARGBType, or VolatileARGBType" );
	}

	/**
	 * Add the given {@code source} to the lists of {@code converterSetups}
	 * (using specified {@code setupId}) and {@code sources}. For this, the
	 * {@code source} is wrapped with a {@link RealARGBColorConverter} and into
	 * a {@link TransformedSource}.
	 *
	 * @param source
	 *            source to add.
	 * @param setupId
	 *            id of the new source for use in {@link bdv.tools.brightness.SetupAssignments}.
	 * @param converterSetups
	 *            list of {@link ConverterSetup}s to which the source should be
	 *            added.
	 * @param sources
	 *            list of {@link SourceAndConverter}s to which the source should
	 *            be added.
	 */
	private static < T extends RealType< T > > void addSourceToListsRealType(
			final Source< T > source,
			final int setupId,
			final List< ConverterSetup > converterSetups,
			final List< SourceAndConverter< T > > sources )
	{
		final T type = Util.getTypeFromInterval( source.getSource( 0, 0 ) );
		final double typeMin = Math.max( 0, Math.min( type.getMinValue(), 65535 ) );
		final double typeMax = Math.max( 0, Math.min( type.getMaxValue(), 65535 ) );
		final RealARGBColorConverter< T > converter = RealARGBColorConverter.create( source.getType(), typeMin, typeMax );
		converter.setColor( new ARGBType( 0xffffffff ) );

		final TransformedSource< T > ts = new TransformedSource<>( source );
		final SourceAndConverter< T > soc = new SourceAndConverter<>( ts, converter );

		final RealARGBColorConverterSetup setup = new RealARGBColorConverterSetup( setupId, converter );

		converterSetups.add( setup );
		sources.add( soc );
	}

	/**
	 * Add the given {@code source} to the lists of {@code converterSetups}
	 * (using specified {@code setupId}) and {@code sources}. For this, the
	 * {@code source} is wrapped with a {@link ScaledARGBConverter.ARGB} and
	 * into a {@link TransformedSource}.
	 *
	 * @param source
	 *            source to add.
	 * @param setupId
	 *            id of the new source for use in {@link bdv.tools.brightness.SetupAssignments}.
	 * @param converterSetups
	 *            list of {@link ConverterSetup}s to which the source should be
	 *            added.
	 * @param sources
	 *            list of {@link SourceAndConverter}s to which the source should
	 *            be added.
	 */
	private static void addSourceToListsARGBType(
			final Source< ARGBType > source,
			final int setupId,
			final List< ConverterSetup > converterSetups,
			final List< SourceAndConverter< ARGBType > > sources )
	{
		final TransformedSource< ARGBType > ts = new TransformedSource<>( source );
		final ScaledARGBConverter.ARGB converter = new ScaledARGBConverter.ARGB( 0, 255 );
		final SourceAndConverter< ARGBType > soc = new SourceAndConverter<>( ts, converter );

		final RealARGBColorConverterSetup setup = new RealARGBColorConverterSetup( setupId, converter );

		converterSetups.add( setup );
		sources.add( soc );
	}

	/**
	 * Add the given {@code source} to the lists of {@code converterSetups}
	 * (using specified {@code setupId}) and {@code sources}. For this, the
	 * {@code source} is wrapped with a {@link ScaledARGBConverter.ARGB} and
	 * into a {@link TransformedSource}.
	 *
	 * @param source
	 *            source to add.
	 * @param setupId
	 *            id of the new source for use in {@link bdv.tools.brightness.SetupAssignments}.
	 * @param converterSetups
	 *            list of {@link ConverterSetup}s to which the source should be
	 *            added.
	 * @param sources
	 *            list of {@link SourceAndConverter}s to which the source should
	 *            be added.
	 */
	private static void addSourceToListsVolatileARGBType(
			final Source< VolatileARGBType > source,
			final int setupId,
			final List< ConverterSetup > converterSetups,
			final List< SourceAndConverter< VolatileARGBType > > sources )
	{
		final TransformedSource< VolatileARGBType > ts = new TransformedSource<>( source );
		final ScaledARGBConverter.VolatileARGB converter = new ScaledARGBConverter.VolatileARGB( 0, 255 );
		final SourceAndConverter< VolatileARGBType > soc = new SourceAndConverter<>( ts, converter );

		final RealARGBColorConverterSetup setup = new RealARGBColorConverterSetup( setupId, converter );

		converterSetups.add( setup );
		sources.add( soc );
	}
}
