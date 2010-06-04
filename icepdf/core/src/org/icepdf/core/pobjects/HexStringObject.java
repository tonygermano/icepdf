/*
 * Version: MPL 1.1/GPL 2.0/LGPL 2.1
 *
 * "The contents of this file are subject to the Mozilla Public License
 * Version 1.1 (the "License"); you may not use this file except in
 * compliance with the License. You may obtain a copy of the License at
 * http://www.mozilla.org/MPL/
 *
 * Software distributed under the License is distributed on an "AS IS"
 * basis, WITHOUT WARRANTY OF ANY KIND, either express or implied. See the
 * License for the specific language governing rights and limitations under
 * the License.
 *
 * The Original Code is ICEpdf 3.0 open source software code, released
 * May 1st, 2009. The Initial Developer of the Original Code is ICEsoft
 * Technologies Canada, Corp. Portions created by ICEsoft are Copyright (C)
 * 2004-2009 ICEsoft Technologies Canada, Corp. All Rights Reserved.
 *
 * Contributor(s): _____________________.
 *
 * Alternatively, the contents of this file may be used under the terms of
 * the GNU Lesser General Public License Version 2.1 or later (the "LGPL"
 * License), in which case the provisions of the LGPL License are
 * applicable instead of those above. If you wish to allow use of your
 * version of this file only under the terms of the LGPL License and not to
 * allow others to use your version of this file under the MPL, indicate
 * your decision by deleting the provisions above and replace them with
 * the notice and other provisions required by the LGPL License. If you do
 * not delete the provisions above, a recipient may use your version of
 * this file under either the MPL or the LGPL License."
 *
 */
package org.icepdf.core.pobjects;

import org.icepdf.core.pobjects.fonts.Font;
import org.icepdf.core.pobjects.fonts.FontFile;
import org.icepdf.core.pobjects.security.SecurityManager;
import org.icepdf.core.util.Utils;

import java.util.logging.Logger;
import java.util.logging.Level;

/**
 * <p>This class represents a PDF Hexadecimal String Object.  Hexadecimal String
 * objects are written as a sequence of literal characters enclosed in
 * angled brackets <>.</p>
 *
 * @since 2.0
 */
public class HexStringObject implements StringObject {

    private static Logger logger =
            Logger.getLogger(HexStringObject.class.toString());

    // core data used to represent the literal string information
    private StringBuilder stringData;

    // Reference is need for standard encryption
    Reference reference;

    /**
     * <p>Creates a new hexadecimal string object so that it represents the same
     * sequence of bytes as in the bytes argument.  In other words, the
     * initial content of the hexadecimal string is the characters represented
     * by the byte data.</p>
     *
     * @param bytes array of bytes which will be interpreted as hexadecimal
     *              data.
     */
    public HexStringObject(byte[] bytes) {
        this(new StringBuilder(bytes.length).append(new String(bytes)));
    }

    /**
     * <p>Creates a new hexadecimal string object so that it represents the same
     * sequence of character data specifed by the argument. This constructor should
     * only be used in the context of the parser which has leading and ending
     * angled brackets which are removed by this method.</p>
     *
     * @param stringBuffer the initial contents of the hexadecimal string object
     */
    public HexStringObject(StringBuilder stringBuffer) {
        // remove angled brackets, passed in by parser
        stringBuffer.deleteCharAt(0);
        stringBuffer.deleteCharAt(stringBuffer.length() - 1);
        // append string data
        stringData = new StringBuilder(stringBuffer.length());
        stringData.append(normalizeHex(stringBuffer).toString());
    }

    /**
     * Gets the integer value of the hexidecimal data specified by the start and
     * offset parameters.
     *
     * @param start  the begining index, inclusive
     * @param offset the length of bytes to process
     * @return unsigned integer value of the specifed data range
     */
    public int getUnsignedInt(int start, int offset) {
        if (start < 0 || stringData.length() < (start + offset))
            return 0;
        int unsignedInt = 0;
        try {
            unsignedInt = Integer.parseInt(
                    stringData.substring(start, start + offset), 16);
        }
        catch (NumberFormatException e) {
            if (logger.isLoggable(Level.FINE)) {
                logger.warning("Number Format Exception " + unsignedInt);
            }
        }
        return unsignedInt;
    }

    /**
     * <p>Returns a string representation of the object.
     * The hex data is converted to an equivalent string representation</p>
     *
     * @return a string representing the object.
     */
    public String toString() {
        return getLiteralString();
    }

    /**
     * <p>Gets a hexadecimal String representation of this object's data, which
     * is in fact, the raw data contained in this object</p>
     *
     * @return a String representation of the object's data in hexadecimal notation.
     */
    public String getHexString() {
        return stringData.toString();
    }

    /**
     * <p>Gets a hexadecimal StringBuffer representation of this object's data,
     * which is in fact the raw data contained in this object.</p>
     *
     * @return a StringBufffer representation of the objects data in hexadecimal.
     */
    public StringBuilder getHexStringBuffer() {
        return stringData;
    }

    /**
     * <p>Gets a literal StringBuffer representation of this object's data.
     * The hexadecimal data is converted to an equivalent string representation</p>
     *
     * @return a StringBuffer representation of the object's data.
     */
    public StringBuilder getLiteralStringBuffer() {
        return hexToString(stringData);
    }

    /**
     * <p>Gets a literal String representation of this object's data.
     * The hexadecimal data is converted to an equivalent string representation.</p>
     *
     * @return a String representation of the object's data.
     */
    public String getLiteralString() {
        return hexToString(stringData).toString();
    }

    /**
     * <p>Gets a literal String representation of this object's data using the
     * specifed font and format.  The font is used to verify that the
     * specific character codes can be rendered; if they can not, they may be
     * removed or combined with the next character code to get a displayable
     * character code.
     *
     * @param fontFormat the type of font which will be used to display
     *                   the text.  Valid values are CID_FORMAT and SIMPLE_FORMAT for Adobe
     *                   Composite and Simple font types respectively
     * @param font       font used to render the literal string data.
     * @return StringBuffer which contains all renderaable characters for the
     *         given font.
     */
    public StringBuilder getLiteralStringBuffer(final int fontFormat, FontFile font) {
        if (fontFormat == Font.SIMPLE_FORMAT) {
            int charOffset = 2;
            int length = getLength();
            StringBuilder tmp = new StringBuilder(length);
            int lastIndex = 0;
            int charValue;
            for (int i = 0; i < length; i += charOffset) {
                charValue = getUnsignedInt(i - lastIndex, lastIndex + charOffset);
                // this is important, currently no examples of 0 cid's
                if (charValue > 0 && font.canDisplayEchar((char) charValue)) {
                    tmp.append((char) charValue);
                    lastIndex = 0;
                } else {
                    lastIndex += charOffset;
                }
            }
            return tmp;
        } else if (fontFormat == Font.CID_FORMAT) {
            int charOffset = 4;
            int length = getLength();
            int charValue;
            StringBuilder tmp = new StringBuilder(length);
            for (int i = 0; i < length; i += charOffset) {
                charValue = getUnsignedInt(i, charOffset);
                if (font.canDisplayEchar((char) charValue)) {
                    tmp.append((char) charValue);
                }
            }
            return tmp;
        }
        return null;
    }

    /**
     * The length of the underlying objects data.
     *
     * @return length of object's data.
     */
    public int getLength() {
        return stringData.length();
    }

    /**
     * Utility method to removed all none hex character from the string and
     * ensure that the length is an even length.
     *
     * @param hex hex data to normalize
     * @return normalized pure hex StringBuffer
     */
    private static StringBuilder normalizeHex(StringBuilder hex) {
        // strip and white space
        int length = hex.length();
        for (int i = 0; i < length; i++) {
            if (isNoneHexChar(hex.charAt(i))) {
                hex.deleteCharAt(i);
                length--;
                i--;
            }
        }
        length = hex.length();
        // add 0's to uneven length
        if (length % 2 != 0) {
            hex.append('0');
        }
        if (length > 2 && length % 4 != 0) {
            hex.append("00");
        }
        return hex;
    }

    /**
     * Utility method to test if the char is a none hexadecimal char.
     *
     * @param c charact to text
     * @return true if the character is a none hexadecimal character
     */
    private static boolean isNoneHexChar(char c) {
        // make sure the char is the following
        return !(((c >= 48) && (c <= 57)) || // 0-9
                ((c >= 65) && (c <= 70)) ||  // A-F
                ((c >= 97) && (c <= 102)));  // a-f
    }

    /**
     * Utility method for converting a hexadecimal string to a litteral string.
     *
     * @param hh StringBuffer containing data in hexadecimal form.
     * @return StringBuffer containing data in literal form.
     */
    private StringBuilder hexToString(StringBuilder hh) {
        StringBuilder sb;
        // special case, test for not a 4 byte character code format
        if (!((hh.charAt(0) == 'F' | hh.charAt(0) == 'f')
                && (hh.charAt(1) == 'E' | hh.charAt(1) == 'e')
                && (hh.charAt(2) == 'F' | hh.charAt(2) == 'f')
                && (hh.charAt(3) == 'F') | hh.charAt(3) == 'f')) {
            int length = hh.length();
            sb = new StringBuilder(length / 2);
            String subStr;

            for (int i = 0; i < length; i = i + 2) {
                subStr = hh.substring(i, i + 2);
                sb.append((char) Integer.parseInt(subStr, 16));
            }
            return sb;
        }
        // otherwise, assume 4 byte character codes
        else {
            int length = hh.length();
            sb = new StringBuilder(length / 4);
            String subStr;
            for (int i = 0; i < length; i = i + 4) {
                subStr = hh.substring(i, i + 4);
                sb.append((char) Integer.parseInt(subStr, 16));
            }
            return sb;
        }
    }

    /**
     * Sets the parent PDF object's reference.
     *
     * @param reference parent object reference.
     */
    public void setReference(Reference reference) {
        this.reference = reference;
    }

    /**
     * Sets the parent PDF object's reference.
     *
     * @return returns the reference used for encryption.
     */
    public Reference getReference(){
        return reference;
    }

    /**
     * Gets the decrypted literal string value of the data using the key provided by the
     * security manager.
     *
     * @param securityManager security manager associated with parent document.
     */
    public String getDecryptedLiteralString(SecurityManager securityManager) {
        // get the security manager instance
        if (securityManager != null && reference != null) {
            // get the key
            byte[] key = securityManager.getDecryptionKey();

            // convert string to bytes.
            byte[] textBytes =
                Utils.convertByteCharSequenceToByteArray(stringData);

            // Decrypt String
            textBytes = securityManager.decrypt(reference,
                    key,
                    textBytes);

            // convert back to a string
            return Utils.convertByteArrayToByteString(textBytes);
        }
        return getLiteralString();
    }

}
