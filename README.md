# N5 Viewer [![Build Status](https://travis-ci.org/saalfeldlab/n5-viewer.svg?branch=master)](https://travis-ci.org/saalfeldlab/n5-viewer)
BigDataViewer-based tool for visualizing [N5](https://github.com/saalfeldlab/n5) datasets.

### Usage

The plugin will be available soon through the [N5 update site](https://imagej.net/N5) in Fiji as *Plugins -> BigDataViewer -> N5 Viewer*.
See also the [N5 Fiji plugin](https://github.com/saalfeldlab/n5-ij).

#### Storage options
The plugin supports multiple storage options:
* Filesystem (local/network drives)
* Zarr (local drives)
* HDF5
* Amazon Web Services S3
* Google Cloud Storage

#### Container structure
The plugin allows to select which datasets to open from the N5 container tree. While almost any dataset can be opened as a BigDataViewer source, the plugin also tries to read the metadata from the corresponding `attributes.json` to determine the additional transformations, or treat a group as a multiscale source.

Currently, the plugin supports the following naming and metadata specification (compatible with the previous version of the plugin):
```
└─ group
    ├─── s0 {} 
    ├─── s1 {"downsamplingFactors": [2, 2, 2]}
    ├─── s2 {"downsamplingFactors": [4, 4, 4]}
    ...
```

The downsampling factors values are given as an example and do not necessarily need to be powers of two.

Each scale level can also contain the pixel resolution attribute in one of the following forms:
```
{"pixelResolution": {"unit": "um", "dimensions": [0.097, 0.097, 0.18]}}
```
or
```
{"pixelResolution": [0.097, 0.097, 0.18]}
```
Both options are supported. For the second option with a plain array, the application assumes the unit to be `um`.

The above format is fully compatible with scale pyramids generated by [N5 Spark](https://github.com/saalfeldlab/n5-spark).

DEPRECATED: alternatively, downsampling factors and pixel resolution can be stored in the group N5 attributes as `scales` and `pixelResolution`.

#### Cloud storage access

Fetching data from a cloud storage may require authentication. If the bucket is not public, the application will try to find the user credentials on the local machine and use them to access the bucket.

The local user credentials can be initialized as follows:
* **Google Cloud**:
  Install [Google Cloud SDK](https://cloud.google.com/sdk/docs/) and run the following in the command line:
  ```
  gcloud auth application-default login
  ```
  This will open an OAuth 2.0 web prompt and then store the credentials on the machine.

* **Amazon Web Services**:
  Install [AWS Command Line Interface](https://aws.amazon.com/cli/) and run `aws configure` in the command line. You would need to enter your access key ID, secret key, and geographical region as described [here](https://docs.aws.amazon.com/cli/latest/userguide/cli-chap-getting-started.html#cli-quick-configuration).

#### Cropping tool

The application has a built-in cropping tool for extracing parts of the dataset as a ImageJ image (can be converted to commonly supported formats such as TIFF series).

Place the mouse pointer at the desired center position of the extracted image and hit `SPACE`. The dialog will pop up where you can specify the dimensions of the extracted image.

The image is cropped <i>without</i> respect to the camera orientation, so the cropped image will always contain Z-slices.
