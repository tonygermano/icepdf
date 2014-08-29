/*
 * Copyright 2006-2014 ICEsoft Technologies Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the
 * License. You may obtain a copy of the License at
 *
 *        http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an "AS
 * IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either
 * express or implied. See the License for the specific language
 * governing permissions and limitations under the License.
 */
package org.icepdf.core.pobjects;


import org.icepdf.core.io.BitStream;
import org.icepdf.core.io.SeekableInputConstrainedWrapper;
import org.icepdf.core.pobjects.filters.CCITTFax;
import org.icepdf.core.pobjects.filters.CCITTFaxDecoder;
import org.icepdf.core.pobjects.graphics.*;
import org.icepdf.core.util.Defs;
import org.icepdf.core.util.Library;

import javax.imageio.ImageIO;
import javax.imageio.ImageReadParam;
import javax.imageio.ImageReader;
import javax.imageio.stream.ImageInputStream;
import java.awt.*;
import java.awt.color.ColorSpace;
import java.awt.image.BufferedImage;
import java.awt.image.ColorConvertOp;
import java.awt.image.WritableRaster;
import java.io.BufferedInputStream;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * ImageStream contains image data that is contains in an XObject of subtype
 * Image.
 *
 * @since 5.0
 */
public class ImageStream extends Stream {

    private static final Logger logger =
            Logger.getLogger(ImageStream.class.toString());

    public static final Name TYPE_VALUE = new Name("Image");
    public static final Name BITSPERCOMPONENT_KEY = new Name("BitsPerComponent");
    public static final Name BPC_KEY = new Name("BPC");
    public static final Name DECODE_KEY = new Name("Decode");
    public static final Name D_KEY = new Name("D");
    public static final Name SMASK_KEY = new Name("SMask");
    public static final Name MASK_KEY = new Name("Mask");
    public static final Name JBIG2GLOBALS_KEY = new Name("JBIG2Globals");
    public static final Name DECODEPARMS_KEY = new Name("DecodeParms");
    public static final Name DP_KEY = new Name("DP");
    public static final Name K_KEY = new Name("K");
    public static final Name ENCODEDBYTEALIGN_KEY = new Name("EncodedByteAlign");
    public static final Name COLUMNS_KEY = new Name("Columns");
    public static final Name ROWS_KEY = new Name("Rows");
    public static final Name BLACKIS1_KEY = new Name("BlackIs1");

    // filter names
    protected static final String[] CCITTFAX_DECODE_FILTERS = new String[]{"CCITTFaxDecode", "/CCF", "CCF"};
    protected static final String[] DCT_DECODE_FILTERS = new String[]{"DCTDecode", "/DCT", "DCT"};
    protected static final String[] JBIG2_DECODE_FILTERS = new String[]{"JBIG2Decode"};
    protected static final String[] JPX_DECODE_FILTERS = new String[]{"JPXDecode"};

    // paper size for rare corner case when ccittfax is missing a dimension.
    private static double pageRatio;

    // flag the forces jai to be use over our fax decode class.
    private static boolean forceJaiccittfax;

    private PColorSpace colourSpace;
    private final Object colorSpaceAssignmentLock = new Object();

    private static boolean isLevigoJBIG2ImageReaderClass;

    static {
        // define alternate page size ration w/h, default Legal.
        pageRatio =
                Defs.sysPropertyDouble("org.icepdf.core.pageRatio",
                        8.26 / 11.68);
        // force jai as the default ccittfax decode.
        forceJaiccittfax =
                Defs.sysPropertyBoolean("org.icepdf.core.ccittfax.jai",
                        false);
        try {
            Class.forName("com.levigo.jbig2.JBIG2ImageReader");
            isLevigoJBIG2ImageReaderClass = true;
            logger.info("Levigo JBIG2 image library was found on classpath");
        } catch (ClassNotFoundException e) {
            logger.info("Levigo JBIG2 image library was not found on classpath");
        }
    }

    private int width;
    private int height;

    /**
     * Create a new instance of a Stream.
     *
     * @param l                  library containing a hash of all document objects
     * @param h                  HashMap of parameters specific to the Stream object.
     * @param streamInputWrapper Accessor to stream byte data
     */
    public ImageStream(Library l, HashMap h, SeekableInputConstrainedWrapper streamInputWrapper) {
        super(l, h, streamInputWrapper);
        init();
    }

    public ImageStream(Library l, HashMap h, byte[] rawBytes) {
        super(l, h, rawBytes);
        init();
    }

    public void init() {
        // get dimension of image stream
        width = library.getInt(entries, WIDTH_KEY);
        height = library.getInt(entries, HEIGHT_KEY);
        //  PDF-458 corner case/one off for trying to guess the width or height
        // of an CCITTfax image that is basically the same use as the page, we
        // use the page dimensions to try and determine the page size.
        // This will fail miserably if the image isn't full page.
        if (height == 0) {
            height = (int) ((1 / pageRatio) * width);
        } else if (width == 0) {
            width = (int) (pageRatio * height);
        }
    }

    public int getWidth() {
        return width;
    }

    public int getHeight() {
        return height;
    }


    /**
     * Gets the image object for the given resource.  This method can optionally
     * scale an image to reduce the total memory foot print or to increase the
     * perceived render quality on screen at low zoom levels.
     *
     * @param fill      color value of image
     * @param resources resouces containing image reference
     * @return new image object
     */
    // was synchronized, not think it is needed?
    @SuppressWarnings("unchecked")
    public synchronized BufferedImage getImage(Color fill, Resources resources) {
        // parse colour space, lock is to insure that getColorSpace()
        // will return only after colourSpace has been set.
        synchronized (colorSpaceAssignmentLock) {
            Object o = entries.get(COLORSPACE_KEY);
            if (resources != null && o != null) {
                colourSpace = resources.getColorSpace(o);
            }
            // assume b&w image is no colour space
            if (colourSpace == null) {
                colourSpace = new DeviceGray(library, null);
            }
        }
        // A flag indicating whether the image shall be treated as an image mask
        boolean isImageMask = isImageMask();

        // If this flag is true, the value of BitsPerComponent shall be 1 and
        // Mask and ColorSpace shall not be specified; unmasked areas shall be
        // painted using the current nonstroking colour
        int bitsPerComponent = library.getInt(entries, BITSPERCOMPONENT_KEY);
        if (isImageMask && bitsPerComponent == 0) {
            bitsPerComponent = 1;
        }

        // check for available memory, get colour space and bit count
        // to better estimate size of image in memory
        int colorSpaceCompCount = colourSpace.getNumComponents();

        // parse decode information
        int maxValue = ((int) Math.pow(2, bitsPerComponent)) - 1;
        float[] decode = new float[2 * colorSpaceCompCount];
        List<Number> decodeVec = (List<Number>) library.getObject(entries, DECODE_KEY);
        if (decodeVec == null) {
            // add a decode param for each colour channel.
            for (int i = 0, j = 0; i < colorSpaceCompCount; i++) {
                decode[j++] = 0.0f;
                decode[j++] = 1.0f / maxValue;
            }
        } else {
            for (int i = 0, j = 0; i < colorSpaceCompCount; i++) {
                float Dmin = decodeVec.get(j).floatValue();
                float Dmax = decodeVec.get(j + 1).floatValue();
                decode[j++] = Dmin;
                decode[j++] = (Dmax - Dmin) / maxValue;
            }
        }
        BufferedImage smaskImage = null;
        BufferedImage maskImage = null;
        int[] maskMinRGB = null;
        int[] maskMaxRGB = null;
        int maskMinIndex = -1;
        int maskMaxIndex = -1;
        Object smaskObj = library.getObject(entries, SMASK_KEY);
        Object maskObj = library.getObject(entries, MASK_KEY);

        // If present, this entry shall override the current soft mask in the
        // graphics state, as well as the image’s Mask entry, if any. However,
        // the other transparency-related graphics state parameters—blend mode
        // and alpha constant—shall remain in effect.
        if (smaskObj instanceof Stream) {
            ImageStream smaskStream = (ImageStream) smaskObj;
            if (smaskStream.isImageSubtype()) {
                smaskImage = smaskStream.getImage(fill, resources);
            }
        }

        // An image XObject defining an image mask to be applied to this image
        // ("Explicit Masking"), or an array specifying a range of colours to be
        // applied to it as a colour key mask ("Colour Key Masking").
        if (maskObj != null && smaskImage == null) {
            if (maskObj instanceof Stream) {
                ImageStream maskStream = (ImageStream) maskObj;
                if (maskStream.isImageSubtype()) {
                    maskImage = maskStream.getImage(fill, resources);
                }
            } else if (maskObj instanceof List) {
                List maskVector = (List) maskObj;
                int[] maskMinOrigCompsInt = new int[colorSpaceCompCount];
                int[] maskMaxOrigCompsInt = new int[colorSpaceCompCount];
                for (int i = 0; i < colorSpaceCompCount; i++) {
                    if ((i * 2) < maskVector.size())
                        maskMinOrigCompsInt[i] = ((Number) maskVector.get(i * 2)).intValue();
                    if ((i * 2 + 1) < maskVector.size())
                        maskMaxOrigCompsInt[i] = ((Number) maskVector.get(i * 2 + 1)).intValue();
                }
                if (colourSpace instanceof Indexed) {
                    Indexed icolourSpace = (Indexed) colourSpace;
                    Color[] colors = icolourSpace.accessColorTable();
                    if (colors != null &&
                            maskMinOrigCompsInt.length >= 1 &&
                            maskMaxOrigCompsInt.length >= 1) {
                        maskMinIndex = maskMinOrigCompsInt[0];
                        maskMaxIndex = maskMaxOrigCompsInt[0];
                        if (maskMinIndex >= 0 && maskMinIndex < colors.length &&
                                maskMaxIndex >= 0 && maskMaxIndex < colors.length) {
                            Color minColor = colors[maskMinOrigCompsInt[0]];
                            Color maxColor = colors[maskMaxOrigCompsInt[0]];
                            maskMinRGB = new int[]{minColor.getRed(), minColor.getGreen(), minColor.getBlue()};
                            maskMaxRGB = new int[]{maxColor.getRed(), maxColor.getGreen(), maxColor.getBlue()};
                        }
                    }
                } else {
                    PColorSpace.reverseInPlace(maskMinOrigCompsInt);
                    PColorSpace.reverseInPlace(maskMaxOrigCompsInt);
                    float[] maskMinOrigComps = new float[colorSpaceCompCount];
                    float[] maskMaxOrigComps = new float[colorSpaceCompCount];
                    colourSpace.normaliseComponentsToFloats(maskMinOrigCompsInt, maskMinOrigComps, (1 << bitsPerComponent) - 1);
                    colourSpace.normaliseComponentsToFloats(maskMaxOrigCompsInt, maskMaxOrigComps, (1 << bitsPerComponent) - 1);

                    Color minColor = colourSpace.getColor(maskMinOrigComps);
                    Color maxColor = colourSpace.getColor(maskMaxOrigComps);
                    PColorSpace.reverseInPlace(maskMinOrigComps);
                    PColorSpace.reverseInPlace(maskMaxOrigComps);
                    maskMinRGB = new int[]{minColor.getRed(), minColor.getGreen(), minColor.getBlue()};
                    maskMaxRGB = new int[]{maxColor.getRed(), maxColor.getGreen(), maxColor.getBlue()};
                }
            }
        }

        return getImage(
                colourSpace, fill, width, height,
                colorSpaceCompCount, bitsPerComponent,
                isImageMask,
                decode,
                smaskImage, maskImage,
                maskMinRGB, maskMaxRGB, maskMinIndex, maskMaxIndex);
    }

    /**
     * Utility to to the image work, the public version pretty much just
     * parses out image dictionary parameters.  This method start the actual
     * image decoding.
     *
     * @param colourSpace         colour space of image.
     * @param fill                fill color to aply to image from current graphics context.
     * @param width               width of image.
     * @param height              heigth of image
     * @param colorSpaceCompCount colour space component count, 1, 3, 4 etc.
     * @param bitsPerComponent    number of bits that represent one component.
     * @param isImageMask         boolean flag to use image mask or not.
     * @param decode              decode array, 1,0 or 0,1 can effect colour interpretation.
     * @param sMaskImage          smaask image value, optional.
     * @param maskImage           buffered image image mask to apply to decoded image, optional.
     * @param maskMinRGB          max rgb values for the mask
     * @param maskMaxRGB          min rgb values for the mask.
     * @param maskMinIndex        max indexed colour values for the mask.
     * @param maskMaxIndex        min indexed colour values for the mask.
     * @return buffered image of decoded image stream, null if an error occured.
     */
    private BufferedImage getImage(
            PColorSpace colourSpace, Color fill,
            int width, int height,
            int colorSpaceCompCount,
            int bitsPerComponent,
            boolean isImageMask,
            float[] decode,
            BufferedImage sMaskImage,
            BufferedImage maskImage,
            int[] maskMinRGB, int[] maskMaxRGB,
            int maskMinIndex, int maskMaxIndex) {

        // check to see if we need to create an imge with alpha, a mask
        // will have imageMask=true and in this case we don't need alpha
        BufferedImage decodedImage = null;

        // JPEG writes out image if successful
        if (shouldUseDCTDecode()) {
            decodedImage = dctDecode(width, height, colourSpace, bitsPerComponent, decode);
        }
        // JBIG2 writes out image if successful
        else if (shouldUseJBIG2Decode()) {
            decodedImage = jbig2Decode(width, height, colourSpace, bitsPerComponent);
        }
        // JPEG2000 writes out image if successful
        else if (shouldUseJPXDecode()) {
            decodedImage = jpxDecode(width, height, colourSpace, bitsPerComponent, decode);
        }
        // we have some raw data so, CCITTfax or some other image primitive.
        else {
            byte[] data = getDecodedStreamBytes(
                    width * height
                            * colourSpace.getNumComponents()
                            * bitsPerComponent / 8);
            int dataLength = data.length;
            // CCITTfax data is raw byte decode.
            if (shouldUseCCITTFaxDecode()) {
                // try default ccittfax decode.
                try {
                    // corner case where a user may want to use JAI because of
                    // speed or compatibility requirements.
                    if (forceJaiccittfax) {
                        throw new Throwable("Forcing CCITTFAX decode via JAI");
                    }
                    data = ccittFaxDecode(data, width, height);
                    dataLength = data.length;
                } catch (Throwable e) {
                    // on a failure then fall back to JAI for a try. likely
                    // will not happen.
                    try {
                        decodedImage = CCITTFax.attemptDeriveBufferedImageFromBytes(
                                this, library, entries, fill);
                    } catch (Throwable e1) {
                        // fall back on ccittfax code.
                        data = ccittFaxDecode(data, width, height);
                        dataLength = data.length;
                    }
                    return decodedImage;
                }
            }
            // finally push the bytes though the common image processor to try
            // and build a a Buffered image.
            try {
                decodedImage = ImageUtility.makeImageWithRasterFromBytes(
                        colourSpace, fill,
                        width, height,
                        colorSpaceCompCount,
                        bitsPerComponent,
                        isImageMask,
                        decode,
                        sMaskImage,
                        maskImage,
                        maskMinRGB, maskMaxRGB,
                        maskMinIndex, maskMaxIndex,
                        data, dataLength);
//                ImageUtility.displayImage(decodedImage, pObjectReference.toString());
            } catch (Exception e) {
                logger.log(Level.FINE, "Error building image raster.", e);
            }
        }

        // Fallback image cod the will use pixel primitives to build out the image.
        if (decodedImage == null) {
            byte[] data = getDecodedStreamBytes(
                    width * height
                            * colourSpace.getNumComponents()
                            * bitsPerComponent / 8);
            // decodes the image stream and returns an image object. Legacy fallback
            // code, should never get here, but there are always corner cases. .
            decodedImage = parseImage(
                    width,
                    height,
                    colourSpace,
                    isImageMask,
                    fill,
                    bitsPerComponent,
                    decode,
                    data);
        }
        if (decodedImage != null) {
            //        ImageUtility.displayImage(decodedImage, pObjectReference.toString());
            if (isImageMask) {
                decodedImage = ImageUtility.applyExplicitMask(decodedImage, fill);
            }

            // apply common mask and sMask processing
            if (sMaskImage != null) {
                decodedImage = ImageUtility.applyExplicitSMask(decodedImage, sMaskImage);
            }
            if (maskImage != null) {
                decodedImage = ImageUtility.applyExplicitMask(decodedImage, maskImage);
            }
            //        ImageUtility.displayImage(decodedImage, pObjectReference.toString());

            // with  little luck the image is ready for viewing.
            return decodedImage;
        }
        return null;
    }


    /**
     * The DCTDecode filter decodes grayscale or color image data that has been
     * encoded in the JPEG baseline format.  Because DCTDecode only deals
     * with images, the instance of image is update instead of decoded
     * stream.
     *
     * @return buffered images representation of the decoded JPEG data.  Null
     * if the image could not be properly decoded.
     */
    private BufferedImage dctDecode(
            int width, int height, PColorSpace colourSpace, int bitspercomponent,
            float[] decode) {

        // BIS's buffer size should be equal to mark() size, and greater than data size (below)
        InputStream input = getDecodedByteArrayInputStream();
        // Used to just read 1000, but found a PDF that included thumbnails first
        final int MAX_BYTES_TO_READ_FOR_ENCODING = 2048;
        BufferedInputStream bufferedInput = new BufferedInputStream(
                input, MAX_BYTES_TO_READ_FOR_ENCODING);
        bufferedInput.mark(MAX_BYTES_TO_READ_FOR_ENCODING);

        // We don't use the PColorSpace to determine how to decode the JPEG, because it tends to be wrong
        // Some files say DeviceCMYK, or ICCBased, when neither would work, because it's really YCbCrA
        // What does work though, is to look into the JPEG headers themself, via getJPEGEncoding()

        int jpegEncoding;
        BufferedImage tmpImage = null;
        ImageReader reader = null;
        ImageInputStream imageInputStream = null;
        try {
            // get the full image data.
            byte[] data = getDecodedStreamBytes(
                    width * height
                            * colourSpace.getNumComponents()
                            * bitspercomponent / 8);

            int dataRead = data.length;
            if (dataRead > MAX_BYTES_TO_READ_FOR_ENCODING) {
                dataRead = MAX_BYTES_TO_READ_FOR_ENCODING;
            }
            // check the encoding type for colour conversion.
            jpegEncoding = ImageUtility.getJPEGEncoding(data, dataRead);

            imageInputStream = ImageIO.createImageInputStream(
                    new ByteArrayInputStream(data));

            // get a reader that supports getting the raster.
            Iterator<ImageReader> iter = ImageIO.getImageReaders(imageInputStream);
            while (iter.hasNext()) {
                reader = iter.next();
                if (reader.canReadRaster()) {
                    if (logger.isLoggable(Level.FINER)) {
                        logger.finer("DCTDecode Image reader: " + reader);
                    }
                    break;
                }
            }
            // should never happen but bail on an empty reader.
            if (reader == null) {
                imageInputStream.close();
                return null;
            }
            reader.setInput(imageInputStream, true, true);
            // read the raster data only, as we have our own logic to covert
            // the raster data to RGB colours.
            ImageReadParam param = reader.getDefaultReadParam();
            WritableRaster wr = (WritableRaster) reader.readRaster(0, param);

            if (jpegEncoding == ImageUtility.JPEG_ENC_RGB && bitspercomponent == 8) {
                tmpImage = ImageUtility.convertSpaceToRgb(wr, colourSpace, decode);
            } else if (jpegEncoding == ImageUtility.JPEG_ENC_CMYK && bitspercomponent == 8) {
                tmpImage = ImageUtility.convertCmykToRgb(wr, decode);
            } else if (jpegEncoding == ImageUtility.JPEG_ENC_YCbCr && bitspercomponent == 8) {
                tmpImage = ImageUtility.convertYCbCrToRGB(wr, decode);
            } else if (jpegEncoding == ImageUtility.JPEG_ENC_YCCK && bitspercomponent == 8) {
                // YCCK to RGB works better if an CMYK intermediate is used, but slower.
                tmpImage = ImageUtility.convertYCCKToRgb(wr, decode);
            } else if (jpegEncoding == ImageUtility.JPEG_ENC_GRAY && bitspercomponent == 8) {
                // In DCTDecode with ColorSpace=DeviceGray, the samples are gray values (2000_SID_Service_Info.core)
                // In DCTDecode with ColorSpace=Separation, the samples are Y values (45-14550BGermanForWeb.core AKA 4570.core)
                // Avoid converting images that are already likely gray.
                if (!(colourSpace instanceof DeviceGray) &&
                        !(colourSpace instanceof ICCBased) &&
                        !(colourSpace instanceof Indexed)) {
                    if (colourSpace instanceof Separation &&
                            ((Separation) colourSpace).isNamedColor()) {
                        tmpImage = ImageUtility.convertGrayToRgb(wr, decode);
                        //ImageUtility.makeGrayBufferedImage(wr);
                    } else {
                        tmpImage = ImageUtility.convertSpaceToRgb(wr, colourSpace, decode);
                    }
                } else {
                    tmpImage = ImageUtility.makeGrayBufferedImage(wr);
                }
            } else {
//                if (imageDecoder.getJPEGDecodeParam().getEncodedColorID() ==
//                        com.sun.image.codec.jpeg.JPEGDecodeParam.COLOR_ID_YCbCrA) {
//                    // YCbCrA, which is slightly different than YCCK
//                    ImageUtility.alterRasterYCbCrA2RGBA(wr);
//                    tmpImage = ImageUtility.makeRGBABufferedImage(wr);
//                } else {
                tmpImage = ImageUtility.convertYCbCrToRGB(wr, decode);
            }

        } catch (IOException e) {
            logger.log(Level.FINE, "Problem loading JPEG image via ImageIO: ", e);
        } finally {
            try {
                input.close();
                // clean up the image reader and image stream
                if (reader != null) {
                    reader.dispose();
                }
                if (imageInputStream != null) {
                    imageInputStream.close();
                }
            } catch (IOException e) {
                logger.log(Level.FINE, "Problem loading JPEG image via ImageIO: ", e);
            }
        }
        // legacy fallback code for creating Image.
//        if (tmpImage == null) {
//            try {
//                //System.out.println("Stream.dctDecode()  Toolkit");
//                byte[] data = getDecodedStreamBytes(width * height
//                        * colourSpace.getNumComponents()
//                        * bitspercomponent / 8);
//                if (data != null) {
//                    Image img = Toolkit.getDefaultToolkit().createImage(data);
//                    if (img != null) {
//                        tmpImage = ImageUtility.makeRGBABufferedImageFromImage(img);
//                    }
//                }
//            } catch (Exception e) {
//                logger.log(Level.FINE, "Problem loading JPEG image via Toolkit: ", e);
//            }
//        }
        return tmpImage;
    }

    /**
     * Utility method to decode JBig2 images.
     *
     * @param width  width of image
     * @param height height of image
     * @return buffered image of decoded jbig2 image stream.   Null if an error
     * occured during decode.
     */
    private BufferedImage jbig2Decode(int width, int height,
                                      PColorSpace colourSpace,
                                      int bitspercomponent) {
        BufferedImage tmpImage;

        // get the decode params form the stream
        HashMap decodeParms = library.getDictionary(entries, DECODEPARMS_KEY);
        Stream globalsStream = null;
        if (decodeParms != null) {
            Object jbigGlobals = library.getObject(decodeParms, JBIG2GLOBALS_KEY);
            if (jbigGlobals instanceof Stream) {
                globalsStream = (Stream) jbigGlobals;
            }
        }
        // grab the data,
        byte[] data = getDecodedStreamBytes(
                width * height
                        * colourSpace.getNumComponents()
                        * bitspercomponent / 8);

        // ICEpdf-pro has a commercial license of the levigo library but the OS
        // library can use it to if the project can comply with levigo's open
        // source licence.
        if (isLevigoJBIG2ImageReaderClass) {
            try {
                tmpImage = ImageUtility.proJbig2Decode(
                        ImageIO.createImageInputStream(new ByteArrayInputStream(data)),
                        decodeParms, globalsStream);
            } catch (Exception e) {
                logger.log(Level.WARNING, "Problem loading JBIG2 imageusing Levigo: ", e);
                // fall back and try and load with the OS jbig2 implementation.
                tmpImage = ImageUtility.jbig2Decode(
                        data,
                        decodeParms, globalsStream);
            }
        } else {
            tmpImage = ImageUtility.jbig2Decode(
                    data,
                    decodeParms, globalsStream);
        }
        // apply the fill colour and alpha if masking is enabled.
        return tmpImage;
    }

    /**
     * Utility method to decode JPEG2000 images.
     *
     * @param width            width of image.
     * @param height           height of image.
     * @param colourSpace      colour space to apply to image.
     * @param bitsPerComponent bits used to represent a colour
     * @return buffered image of the jpeg2000 image stream.  Null if a problem
     * occurred during the decode.
     */
    private BufferedImage jpxDecode(int width, int height, PColorSpace colourSpace,
                                    int bitsPerComponent, float[] decode) {
        BufferedImage tmpImage = null;
        try {
            // Verify that ImageIO can read JPEG2000
            Iterator<ImageReader> iterator = ImageIO.getImageReadersByFormatName("JPEG2000");
            if (!iterator.hasNext()) {
                logger.info(
                        "ImageIO missing required plug-in to read JPEG 2000 images. " +
                                "You can download the JAI ImageIO Tools from: " +
                                "http://www.oracle.com/technetwork/java/current-142188.html");
                return null;
            }
            // decode the image.
            byte[] data = getDecodedStreamBytes(
                    width * height
                            * colourSpace.getNumComponents()
                            * bitsPerComponent / 8);
            ImageInputStream imageInputStream = ImageIO.createImageInputStream(
                    new ByteArrayInputStream(data));


            // getting the raster for JPX seems to fail in most cases.
            Iterator<ImageReader> iter = ImageIO.getImageReaders(imageInputStream);
            ImageReader reader = null;
            while (iter.hasNext()) {
                reader = iter.next();
                if (reader.canReadRaster()) {
                    if (logger.isLoggable(Level.FINER)) {
                        logger.finer("JPXDecode Image reader: " + reader);
                    }
                    break;
                }
            }

            // read the raster data only, as we have our own logic to covert
            // the raster data to RGB colours.
            if (reader == null) {
                imageInputStream.close();
                return null;
            }
            ImageReadParam param = reader.getDefaultReadParam();
            reader.setInput(imageInputStream, true, true);
            try {
                tmpImage = reader.read(0, param);
            } finally {
                reader.dispose();
                imageInputStream.close();
            }
            WritableRaster wr = tmpImage.getRaster();

            // special fallback scenario for ICCBased colours.
            if (colourSpace instanceof ICCBased) {
                ICCBased iccBased = (ICCBased) colourSpace;
                // first try and apply the color space
                try {
                    ColorSpace cs = iccBased.getColorSpace();
                    ColorConvertOp cco = new ColorConvertOp(cs, null);
                    tmpImage = ImageUtility.makeRGBBufferedImage(wr);
                    cco.filter(tmpImage, tmpImage);
                } catch (Throwable e) {
                    logger.warning("Error processing ICC Color profile, failing " +
                            "back to alternative.");
                    // set the alternate as the current and try and process
                    // using the below rules.
                    colourSpace = iccBased.getAlternate();
                }
            }
            // apply respective colour models to the JPEG2000 image.
            if (colourSpace instanceof DeviceRGB && bitsPerComponent == 8) {
                tmpImage = ImageUtility.convertSpaceToRgb(wr, colourSpace, decode);
            } else if (colourSpace instanceof DeviceCMYK && bitsPerComponent == 8) {
                tmpImage = ImageUtility.convertCmykToRgb(wr, decode);
            } else if ((colourSpace instanceof DeviceGray)
                    && bitsPerComponent == 8) {
                tmpImage = ImageUtility.makeGrayBufferedImage(wr);
            } else if (colourSpace instanceof Separation) {
                if (colourSpace instanceof Separation &&
                        ((Separation) colourSpace).isNamedColor()) {
                    tmpImage = ImageUtility.convertGrayToRgb(wr, decode);
//                    tmpImage = ImageUtility.makeGrayBufferedImage(wr);
                } else {
                    tmpImage = ImageUtility.convertSpaceToRgb(wr, colourSpace, decode);
                }
            } else if (colourSpace instanceof Indexed) {
                // still some issue here with Chevron.pdf
                tmpImage = ImageUtility.applyIndexColourModel(wr, colourSpace, bitsPerComponent);
            }
        } catch (IOException e) {
            logger.log(Level.FINE, "Problem loading JPEG2000 image: ", e);
        }
        return tmpImage;
    }

    /**
     * CCITT fax decode algorithm, decodes the stream into a valid image
     * stream that can be used to create a BufferedImage.
     *
     * @param width  of image
     * @param height height of image.
     * @return decoded stream bytes.
     */
    private byte[] ccittFaxDecode(byte[] streamData, int width, int height) {
        HashMap decodeParms = library.getDictionary(entries, DECODEPARMS_KEY);
        float k = library.getFloat(decodeParms, K_KEY);
        // default value is always false
        boolean blackIs1 = getBlackIs1(library, decodeParms);
        // double check for blackIs1 in the main dictionary.
        if (!blackIs1) {
            blackIs1 = getBlackIs1(library, entries);
        }
        // get value of key if it is available.
        boolean encodedByteAlign = false;
        Object encodedByteAlignObject = library.getObject(decodeParms, ENCODEDBYTEALIGN_KEY);
        if (encodedByteAlignObject instanceof Boolean) {
            encodedByteAlign = (Boolean) encodedByteAlignObject;
        }
        int columns = library.getInt(decodeParms, COLUMNS_KEY);
        int rows = library.getInt(decodeParms, ROWS_KEY);

        if (columns == 0) {
            columns = width;
        }
        if (rows == 0) {
            rows = height;
        }
        int size = rows * ((columns + 7) >> 3);
        byte[] decodedStreamData = new byte[size];
        CCITTFaxDecoder decoder = new CCITTFaxDecoder(1, columns, rows);
        decoder.setAlign(encodedByteAlign);
        // pick three three possible fax encoding.
        try {
            if (k == 0) {
                decoder.decodeT41D(decodedStreamData, streamData, 0, rows);
            } else if (k > 0) {
                decoder.decodeT42D(decodedStreamData, streamData, 0, rows);
            } else if (k < 0) {
                decoder.decodeT6(decodedStreamData, streamData, 0, rows);
            }
        } catch (Exception e) {
            logger.warning("Error decoding CCITTFax image k: " + k);
            // IText 5.03 doesn't correctly assign a k value for the deocde,
            // as  result we can try one more time using the T6.
            decoder.decodeT6(decodedStreamData, streamData, 0, rows);
        }
        // check the black is value flag, no one likes inverted colours.
        if (!blackIs1) {
            // toggle the byte data invert colour, not bit operand.
            for (int i = 0; i < decodedStreamData.length; i++) {
                decodedStreamData[i] = (byte) ~decodedStreamData[i];
            }
        }
        return decodedStreamData;
    }


    /**
     * Parses the image stream and creates a Java Images object based on the
     * the given stream and the supporting paramaters.
     *
     * @param width         dimension of new image
     * @param height        dimension of new image
     * @param colorSpace    colour space of image
     * @param imageMask     true if the image has a imageMask, false otherwise
     * @param bitsPerColour number of bits used in a colour
     * @param decode        Decode attribute values from PObject
     * @return valid java image from the PDF stream
     */
    private BufferedImage parseImage(
            int width,
            int height,
            PColorSpace colorSpace,
            boolean imageMask,
            Color fill,
            int bitsPerColour,
            float[] decode,
            byte[] baCCITTFaxData) {

        // store for manipulating bits in image
        int[] imageBits = new int[width];

        // RGB value for colour used as fill for image
        int fillRGB = fill.getRGB();

        // Number of colour components in image, should be 3 for RGB or 4
        // for ARGB.
        int colorSpaceCompCount = colorSpace.getNumComponents();
        boolean isDeviceRGB = colorSpace instanceof DeviceRGB;
        boolean isDeviceGray = colorSpace instanceof DeviceGray;

        // Max value used to represent a colour,  usually 255, min is 0
        int maxColourValue = ((1 << bitsPerColour) - 1);

        int f[] = new int[colorSpaceCompCount];
        float ff[] = new float[colorSpaceCompCount];

        // image mask from
        float imageMaskValue = decode[0];

        // Create the memory hole where where the buffered image will be writen
        // too, bit by painfull bit.
        BufferedImage bim = new BufferedImage(width, height, BufferedImage.TYPE_INT_ARGB);

        // create the buffer and get the first series of bytes from the cached
        // stream
        BitStream in;
        if (baCCITTFaxData != null) {
            in = new BitStream(new ByteArrayInputStream(baCCITTFaxData));
        } else {
            InputStream dataInput = getDecodedByteArrayInputStream();
            if (dataInput == null)
                return null;
            in = new BitStream(dataInput);
        }

        try {
            // Start encoding bit stream into an image,  we work one pixel at
            // a time,  and grap the need bit information for the images
            // colour space and bits per colour
            for (int y = 0; y < height; y++) {

                for (int x = 0; x < width; x++) {

                    // if image has mask apply it
                    if (imageMask) {
                        int bit = in.getBits(bitsPerColour);
                        bit = (bit == imageMaskValue) ? fillRGB : 0x00000000;
                        imageBits[x] = bit;
                    }
                    // other wise start colour bit parsing
                    else {
                        // set some default values
                        int red = 255;
                        int blue = 255;
                        int green = 255;
                        int alpha = 255;

                        // indexed colour
                        if (colorSpaceCompCount == 1) {
                            // get value used for this bit
                            int bit = in.getBits(bitsPerColour);
                            // check decode array if a colour inversion is needed
                            if (decode != null) {
                                // if index 0 > index 1 then we have a need for ainversion
                                if (decode[0] > decode[1]) {
                                    bit = (bit == maxColourValue) ? 0x00000000 : maxColourValue;
                                }
                            }

                            if (isDeviceGray) {
                                if (bitsPerColour == 1)
                                    bit = ImageUtility.GRAY_1_BIT_INDEX_TO_RGB[bit];
                                else if (bitsPerColour == 2)
                                    bit = ImageUtility.GRAY_2_BIT_INDEX_TO_RGB[bit];
                                else if (bitsPerColour == 4)
                                    bit = ImageUtility.GRAY_4_BIT_INDEX_TO_RGB[bit];
                                else if (bitsPerColour == 8) {
                                    bit = ((bit << 24) |
                                            (bit << 16) |
                                            (bit << 8) |
                                            bit);
                                }
                                imageBits[x] = bit;
                            } else {
                                f[0] = bit;
                                colorSpace.normaliseComponentsToFloats(f, ff, maxColourValue);

                                Color color = colorSpace.getColor(ff);
                                imageBits[x] = color.getRGB();
                            }
                        }
                        // normal RGB colour
                        else if (colorSpaceCompCount == 3) {
                            // We can have an ICCBased color space that has 3 components,
                            //  but where we can't assume it's RGB.
                            // But, when it is DeviceRGB, we don't want the performance hit
                            //  of converting the pixels via the PColorSpace, so we'll
                            //  break this into the two cases
                            if (isDeviceRGB) {
                                red = in.getBits(bitsPerColour);
                                green = in.getBits(bitsPerColour);
                                blue = in.getBits(bitsPerColour);
                                // combine the colour together
                                imageBits[x] = (alpha << 24) | (red << 16) |
                                        (green << 8) | blue;
                            } else {
                                for (int i = 0; i < colorSpaceCompCount; i++) {
                                    f[i] = in.getBits(bitsPerColour);
                                }
                                PColorSpace.reverseInPlace(f);
                                colorSpace.normaliseComponentsToFloats(f, ff, maxColourValue);
                                Color color = colorSpace.getColor(ff);
                                imageBits[x] = color.getRGB();
                            }
                        }
                        // normal aRGB colour,  this could use some more
                        // work for optimizing.
                        else if (colorSpaceCompCount == 4) {
                            for (int i = 0; i < colorSpaceCompCount; i++) {
                                f[i] = in.getBits(bitsPerColour);
                                // apply decode
                                if (decode[0] > decode[1]) {
                                    f[i] = maxColourValue - f[i];
                                }
                            }
                            PColorSpace.reverseInPlace(f);
                            colorSpace.normaliseComponentsToFloats(f, ff, maxColourValue);
                            Color color = colorSpace.getColor(ff);
                            imageBits[x] = color.getRGB();
                        }
                        // else just set pixel with the default values
                        else {
                            // compine the colour together
                            imageBits[x] = (alpha << 24) | (red << 16) |
                                    (green << 8) | blue;
                        }
                    }
                }
                // Assign the new bits for this pixel
                bim.setRGB(0, y, width, 1, imageBits, 0, 1);
            }
            // final clean up.
            in.close();
        } catch (IOException e) {
            logger.log(Level.FINE, "Error parsing image.", e);
        }

        return bim;
    }

    /**
     * If BlackIs1 was not specified, then return null, instead of the
     * default value of false, so we can tell if it was given or not
     */
    public boolean getBlackIs1(Library library, HashMap decodeParmsDictionary) {
        Object blackIs1Obj = library.getObject(decodeParmsDictionary, BLACKIS1_KEY);
        if (blackIs1Obj != null) {
            if (blackIs1Obj instanceof Boolean) {
                return (Boolean) blackIs1Obj;
            } else if (blackIs1Obj instanceof String) {
                String blackIs1String = (String) blackIs1Obj;
                if (blackIs1String.equalsIgnoreCase("true"))
                    return true;
                else if (blackIs1String.equalsIgnoreCase("t"))
                    return true;
                else if (blackIs1String.equals("1"))
                    return true;
                else if (blackIs1String.equalsIgnoreCase("false"))
                    return false;
                else if (blackIs1String.equalsIgnoreCase("f"))
                    return false;
                else if (blackIs1String.equals("0"))
                    return false;
            }
        }
        return false;
    }

    private boolean containsFilter(String[] searchFilterNames) {
        List filterNames = getFilterNames();
        if (filterNames == null)
            return false;
        for (Object filterName1 : filterNames) {
            String filterName = filterName1.toString();
            for (String search : searchFilterNames) {
                if (search.equals(filterName)) {
                    return true;
                }
            }
        }
        return false;
    }

    /**
     * Does the image have an ImageMask.
     */
    public boolean isImageMask() {
        return library.getBoolean(entries, IMAGEMASK_KEY);
    }

    private boolean shouldUseCCITTFaxDecode() {
        return containsFilter(CCITTFAX_DECODE_FILTERS);
    }

    private boolean shouldUseDCTDecode() {
        return containsFilter(DCT_DECODE_FILTERS);
    }

    private boolean shouldUseJBIG2Decode() {
        return containsFilter(JBIG2_DECODE_FILTERS);
    }

    private boolean shouldUseJPXDecode() {
        return containsFilter(JPX_DECODE_FILTERS);
    }

    /**
     * Used to enable/disable the loading of CCITTFax images using JAI library.
     * This method can be used in place of the system property
     * org.icepdf.core.ccittfax.jai .
     *
     * @param enable eanb
     */
    public static void forceJaiCcittFax(boolean enable) {
        forceJaiccittfax = enable;
    }

    public PColorSpace getColourSpace() {
        synchronized (colorSpaceAssignmentLock) {
            return colourSpace;
        }
    }

    /**
     * Return a string description of the object.  Primarily used for debugging.
     */
    public String toString() {
        StringBuilder sb = new StringBuilder(64);
        sb.append("Image stream= ");
        sb.append(entries);
        if (getPObjectReference() != null) {
            sb.append("  ");
            sb.append(getPObjectReference());
        }
        return sb.toString();
    }


}
