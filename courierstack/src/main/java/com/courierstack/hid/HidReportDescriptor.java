package com.courierstack.hid;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Stack;

/**
 * Parses and represents HID Report Descriptors.
 *
 * <p>A report descriptor defines the format and meaning of HID reports.
 * It uses a compact binary format with items that describe the report
 * structure, usage pages, logical ranges, and data formats.
 *
 * <p>This parser extracts:
 * <ul>
 *   <li>Report sizes for each report type and ID</li>
 *   <li>Field definitions including usage, logical min/max, etc.</li>
 *   <li>Collection hierarchy (Application, Physical, etc.)</li>
 *   <li>Device type detection (keyboard, mouse, gamepad, etc.)</li>
 * </ul>
 *
 * <p>Thread safety: This class is immutable after construction.
 *
 * @see HidConstants
 * @see HidReport
 */
public class HidReportDescriptor {

    private final byte[] rawDescriptor;
    private final List<ReportField> inputFields;
    private final List<ReportField> outputFields;
    private final List<ReportField> featureFields;
    private final List<Collection> collections;
    private final Map<Integer, Integer> inputReportSizes;
    private final Map<Integer, Integer> outputReportSizes;
    private final Map<Integer, Integer> featureReportSizes;
    private final boolean usesReportIds;
    private final int deviceType;
    private final List<Item> items;

    // ==================== Parsing ====================

    /**
     * Parses a HID report descriptor.
     *
     * @param descriptor raw descriptor bytes
     * @return parsed descriptor
     * @throws NullPointerException     if descriptor is null
     * @throws IllegalArgumentException if descriptor is malformed
     */
    public static HidReportDescriptor parse(byte[] descriptor) {
        Objects.requireNonNull(descriptor, "descriptor must not be null");
        return new HidReportDescriptor(descriptor);
    }

    private HidReportDescriptor(byte[] descriptor) {
        this.rawDescriptor = descriptor.clone();
        this.inputFields = new ArrayList<>();
        this.outputFields = new ArrayList<>();
        this.featureFields = new ArrayList<>();
        this.collections = new ArrayList<>();
        this.inputReportSizes = new HashMap<>();
        this.outputReportSizes = new HashMap<>();
        this.featureReportSizes = new HashMap<>();
        this.items = new ArrayList<>();

        ParseState state = new ParseState();
        parseDescriptor(state);

        this.usesReportIds = !inputReportSizes.isEmpty() &&
                            (inputReportSizes.size() > 1 || !inputReportSizes.containsKey(0));
        this.deviceType = detectDeviceType();
    }

    private void parseDescriptor(ParseState state) {
        ByteBuffer buf = ByteBuffer.wrap(rawDescriptor).order(ByteOrder.LITTLE_ENDIAN);

        while (buf.hasRemaining()) {
            int pos = buf.position();
            int prefix = buf.get() & 0xFF;

            // Long item format check
            if (prefix == 0xFE) {
                if (buf.remaining() < 2) break;
                int dataSize = buf.get() & 0xFF;
                int longTag = buf.get() & 0xFF;
                if (buf.remaining() < dataSize) break;
                byte[] data = new byte[dataSize];
                buf.get(data);
                items.add(new Item(pos, HidConstants.ITEM_TYPE_RESERVED, longTag, data));
                continue;
            }

            // Short item format
            int size = prefix & 0x03;
            if (size == 3) size = 4; // Size encoding: 0=0, 1=1, 2=2, 3=4
            int type = (prefix >> 2) & 0x03;
            int tag = (prefix >> 4) & 0x0F;

            if (buf.remaining() < size) break;

            byte[] data = new byte[size];
            buf.get(data);

            long value = parseItemValue(data, size);
            items.add(new Item(pos, type, tag, data));

            processItem(state, type, tag, value, (int) value);
        }
    }

    private long parseItemValue(byte[] data, int size) {
        if (size == 0) return 0;
        long value = 0;
        for (int i = 0; i < size; i++) {
            value |= ((long) (data[i] & 0xFF)) << (i * 8);
        }
        // Sign extend for signed values
        if (size == 1 && (value & 0x80) != 0) {
            value |= 0xFFFFFFFFFFFFFF00L;
        } else if (size == 2 && (value & 0x8000) != 0) {
            value |= 0xFFFFFFFFFFFF0000L;
        } else if (size == 4 && (value & 0x80000000L) != 0) {
            value |= 0xFFFFFFFF00000000L;
        }
        return value;
    }

    private void processItem(ParseState state, int type, int tag, long signedValue, int unsignedValue) {
        switch (type) {
            case HidConstants.ITEM_TYPE_MAIN:
                processMainItem(state, tag, unsignedValue);
                break;
            case HidConstants.ITEM_TYPE_GLOBAL:
                processGlobalItem(state, tag, signedValue, unsignedValue);
                break;
            case HidConstants.ITEM_TYPE_LOCAL:
                processLocalItem(state, tag, unsignedValue);
                break;
        }
    }

    private void processMainItem(ParseState state, int tag, int value) {
        switch (tag) {
            case HidConstants.MAIN_INPUT:
                addFields(state, HidConstants.REPORT_TYPE_INPUT, value);
                break;
            case HidConstants.MAIN_OUTPUT:
                addFields(state, HidConstants.REPORT_TYPE_OUTPUT, value);
                break;
            case HidConstants.MAIN_FEATURE:
                addFields(state, HidConstants.REPORT_TYPE_FEATURE, value);
                break;
            case HidConstants.MAIN_COLLECTION:
                Collection collection = new Collection(
                        value,
                        state.usagePage,
                        state.usages.isEmpty() ? 0 : state.usages.get(0),
                        state.collectionStack.isEmpty() ? null : state.collectionStack.peek()
                );
                collections.add(collection);
                state.collectionStack.push(collection);
                break;
            case HidConstants.MAIN_END_COLLECTION:
                if (!state.collectionStack.isEmpty()) {
                    state.collectionStack.pop();
                }
                break;
        }
        // Clear local state after main item
        state.usages.clear();
        state.usageMin = 0;
        state.usageMax = 0;
        state.stringIndex = 0;
        state.designatorIndex = 0;
    }

    private void processGlobalItem(ParseState state, int tag, long signedValue, int unsignedValue) {
        switch (tag) {
            case HidConstants.GLOBAL_USAGE_PAGE:
                state.usagePage = unsignedValue;
                break;
            case HidConstants.GLOBAL_LOGICAL_MIN:
                state.logicalMin = (int) signedValue;
                break;
            case HidConstants.GLOBAL_LOGICAL_MAX:
                state.logicalMax = (int) signedValue;
                break;
            case HidConstants.GLOBAL_PHYSICAL_MIN:
                state.physicalMin = (int) signedValue;
                break;
            case HidConstants.GLOBAL_PHYSICAL_MAX:
                state.physicalMax = (int) signedValue;
                break;
            case HidConstants.GLOBAL_UNIT_EXPONENT:
                state.unitExponent = (int) signedValue;
                break;
            case HidConstants.GLOBAL_UNIT:
                state.unit = unsignedValue;
                break;
            case HidConstants.GLOBAL_REPORT_SIZE:
                state.reportSize = unsignedValue;
                break;
            case HidConstants.GLOBAL_REPORT_ID:
                state.reportId = unsignedValue;
                break;
            case HidConstants.GLOBAL_REPORT_COUNT:
                state.reportCount = unsignedValue;
                break;
            case HidConstants.GLOBAL_PUSH:
                state.globalStack.push(state.cloneGlobalState());
                break;
            case HidConstants.GLOBAL_POP:
                if (!state.globalStack.isEmpty()) {
                    state.restoreGlobalState(state.globalStack.pop());
                }
                break;
        }
    }

    private void processLocalItem(ParseState state, int tag, int value) {
        switch (tag) {
            case HidConstants.LOCAL_USAGE:
                state.usages.add(value);
                break;
            case HidConstants.LOCAL_USAGE_MIN:
                state.usageMin = value;
                break;
            case HidConstants.LOCAL_USAGE_MAX:
                state.usageMax = value;
                break;
            case HidConstants.LOCAL_DESIGNATOR_INDEX:
                state.designatorIndex = value;
                break;
            case HidConstants.LOCAL_STRING_INDEX:
                state.stringIndex = value;
                break;
        }
    }

    private void addFields(ParseState state, int reportType, int flags) {
        int totalBits = state.reportSize * state.reportCount;

        // Create field definition
        ReportField field = new ReportField(
                reportType,
                state.reportId,
                state.usagePage,
                state.usages.isEmpty() ? 0 : state.usages.get(0),
                state.usageMin,
                state.usageMax,
                state.logicalMin,
                state.logicalMax,
                state.physicalMin,
                state.physicalMax,
                state.reportSize,
                state.reportCount,
                flags,
                getFieldBitOffset(state, reportType),
                state.unit,
                state.unitExponent
        );

        // Track all usages for this field
        if (state.usageMin != 0 && state.usageMax != 0) {
            for (int u = state.usageMin; u <= state.usageMax; u++) {
                field.addUsage(u);
            }
        } else {
            for (int u : state.usages) {
                field.addUsage(u);
            }
        }

        // Add to appropriate list
        switch (reportType) {
            case HidConstants.REPORT_TYPE_INPUT:
                inputFields.add(field);
                updateReportSize(inputReportSizes, state.reportId, totalBits);
                break;
            case HidConstants.REPORT_TYPE_OUTPUT:
                outputFields.add(field);
                updateReportSize(outputReportSizes, state.reportId, totalBits);
                break;
            case HidConstants.REPORT_TYPE_FEATURE:
                featureFields.add(field);
                updateReportSize(featureReportSizes, state.reportId, totalBits);
                break;
        }
    }

    private int getFieldBitOffset(ParseState state, int reportType) {
        Map<Integer, Integer> sizeMap;
        switch (reportType) {
            case HidConstants.REPORT_TYPE_INPUT: sizeMap = inputReportSizes; break;
            case HidConstants.REPORT_TYPE_OUTPUT: sizeMap = outputReportSizes; break;
            case HidConstants.REPORT_TYPE_FEATURE: sizeMap = featureReportSizes; break;
            default: return 0;
        }
        return sizeMap.getOrDefault(state.reportId, 0);
    }

    private void updateReportSize(Map<Integer, Integer> sizeMap, int reportId, int bits) {
        sizeMap.merge(reportId, bits, Integer::sum);
    }

    private int detectDeviceType() {
        boolean hasKeyboard = false;
        boolean hasMouse = false;
        boolean hasGamepad = false;
        boolean hasJoystick = false;
        boolean hasConsumer = false;
        boolean hasDigitizer = false;

        for (Collection col : collections) {
            if (col.type == HidConstants.COLLECTION_APPLICATION) {
                if (col.usagePage == HidConstants.USAGE_PAGE_GENERIC_DESKTOP) {
                    switch (col.usage) {
                        case HidConstants.USAGE_KEYBOARD:
                        case HidConstants.USAGE_KEYPAD:
                            hasKeyboard = true;
                            break;
                        case HidConstants.USAGE_MOUSE:
                        case HidConstants.USAGE_POINTER:
                            hasMouse = true;
                            break;
                        case HidConstants.USAGE_GAMEPAD:
                            hasGamepad = true;
                            break;
                        case HidConstants.USAGE_JOYSTICK:
                            hasJoystick = true;
                            break;
                    }
                } else if (col.usagePage == HidConstants.USAGE_PAGE_CONSUMER) {
                    hasConsumer = true;
                } else if (col.usagePage == HidConstants.USAGE_PAGE_DIGITIZER) {
                    hasDigitizer = true;
                }
            }
        }

        // Prioritize device type detection
        if (hasKeyboard && hasMouse) {
            return HidConstants.DEVICE_TYPE_KEYBOARD_MOUSE_COMBO;
        } else if (hasKeyboard) {
            return HidConstants.DEVICE_TYPE_KEYBOARD;
        } else if (hasMouse) {
            return HidConstants.DEVICE_TYPE_MOUSE;
        } else if (hasGamepad) {
            return HidConstants.DEVICE_TYPE_GAMEPAD;
        } else if (hasJoystick) {
            return HidConstants.DEVICE_TYPE_JOYSTICK;
        } else if (hasConsumer) {
            return HidConstants.DEVICE_TYPE_REMOTE;
        } else if (hasDigitizer) {
            return HidConstants.DEVICE_TYPE_DIGITIZER;
        }

        return HidConstants.DEVICE_TYPE_UNKNOWN;
    }

    // ==================== Accessors ====================

    /**
     * Returns a copy of the raw descriptor bytes.
     *
     * @return raw descriptor
     */
    public byte[] getRawDescriptor() {
        return rawDescriptor.clone();
    }

    /**
     * Returns the raw descriptor length.
     *
     * @return length in bytes
     */
    public int getLength() {
        return rawDescriptor.length;
    }

    /**
     * Returns whether this descriptor uses report IDs.
     *
     * @return true if report IDs are used
     */
    public boolean usesReportIds() {
        return usesReportIds;
    }

    /**
     * Returns the detected device type.
     *
     * @return device type constant
     */
    public int getDeviceType() {
        return deviceType;
    }

    /**
     * Returns the detected device type name.
     *
     * @return device type name
     */
    public String getDeviceTypeName() {
        return HidConstants.getDeviceTypeName(deviceType);
    }

    // ==================== Report Sizes ====================

    /**
     * Returns the size of an input report in bits.
     *
     * @param reportId report ID (0 if not using report IDs)
     * @return size in bits, or 0 if not found
     */
    public int getInputReportBits(int reportId) {
        return inputReportSizes.getOrDefault(reportId, 0);
    }

    /**
     * Returns the size of an input report in bytes.
     *
     * @param reportId report ID
     * @return size in bytes
     */
    public int getInputReportBytes(int reportId) {
        return (getInputReportBits(reportId) + 7) / 8;
    }

    /**
     * Returns the size of an output report in bits.
     *
     * @param reportId report ID
     * @return size in bits
     */
    public int getOutputReportBits(int reportId) {
        return outputReportSizes.getOrDefault(reportId, 0);
    }

    /**
     * Returns the size of an output report in bytes.
     *
     * @param reportId report ID
     * @return size in bytes
     */
    public int getOutputReportBytes(int reportId) {
        return (getOutputReportBits(reportId) + 7) / 8;
    }

    /**
     * Returns the size of a feature report in bits.
     *
     * @param reportId report ID
     * @return size in bits
     */
    public int getFeatureReportBits(int reportId) {
        return featureReportSizes.getOrDefault(reportId, 0);
    }

    /**
     * Returns the size of a feature report in bytes.
     *
     * @param reportId report ID
     * @return size in bytes
     */
    public int getFeatureReportBytes(int reportId) {
        return (getFeatureReportBits(reportId) + 7) / 8;
    }

    /**
     * Returns all input report IDs.
     *
     * @return list of report IDs
     */
    public List<Integer> getInputReportIds() {
        return new ArrayList<>(inputReportSizes.keySet());
    }

    /**
     * Returns all output report IDs.
     *
     * @return list of report IDs
     */
    public List<Integer> getOutputReportIds() {
        return new ArrayList<>(outputReportSizes.keySet());
    }

    /**
     * Returns all feature report IDs.
     *
     * @return list of report IDs
     */
    public List<Integer> getFeatureReportIds() {
        return new ArrayList<>(featureReportSizes.keySet());
    }

    // ==================== Fields ====================

    /**
     * Returns all input fields.
     *
     * @return unmodifiable list of input fields
     */
    public List<ReportField> getInputFields() {
        return Collections.unmodifiableList(inputFields);
    }

    /**
     * Returns all output fields.
     *
     * @return unmodifiable list of output fields
     */
    public List<ReportField> getOutputFields() {
        return Collections.unmodifiableList(outputFields);
    }

    /**
     * Returns all feature fields.
     *
     * @return unmodifiable list of feature fields
     */
    public List<ReportField> getFeatureFields() {
        return Collections.unmodifiableList(featureFields);
    }

    /**
     * Returns input fields for a specific report ID.
     *
     * @param reportId report ID
     * @return list of fields
     */
    public List<ReportField> getInputFields(int reportId) {
        List<ReportField> result = new ArrayList<>();
        for (ReportField field : inputFields) {
            if (field.reportId == reportId) {
                result.add(field);
            }
        }
        return result;
    }

    /**
     * Finds a field by usage page and usage.
     *
     * @param usagePage usage page
     * @param usage     usage ID
     * @return field, or null if not found
     */
    public ReportField findField(int usagePage, int usage) {
        for (ReportField field : inputFields) {
            if (field.usagePage == usagePage && field.hasUsage(usage)) {
                return field;
            }
        }
        for (ReportField field : outputFields) {
            if (field.usagePage == usagePage && field.hasUsage(usage)) {
                return field;
            }
        }
        for (ReportField field : featureFields) {
            if (field.usagePage == usagePage && field.hasUsage(usage)) {
                return field;
            }
        }
        return null;
    }

    // ==================== Collections ====================

    /**
     * Returns all collections.
     *
     * @return unmodifiable list of collections
     */
    public List<Collection> getCollections() {
        return Collections.unmodifiableList(collections);
    }

    /**
     * Returns top-level application collections.
     *
     * @return list of application collections
     */
    public List<Collection> getApplicationCollections() {
        List<Collection> result = new ArrayList<>();
        for (Collection col : collections) {
            if (col.type == HidConstants.COLLECTION_APPLICATION && col.parent == null) {
                result.add(col);
            }
        }
        return result;
    }

    // ==================== Items ====================

    /**
     * Returns all parsed items.
     *
     * @return unmodifiable list of items
     */
    public List<Item> getItems() {
        return Collections.unmodifiableList(items);
    }

    // ==================== Object Methods ====================

    @Override
    public String toString() {
        StringBuilder sb = new StringBuilder();
        sb.append("HidReportDescriptor{");
        sb.append("length=").append(rawDescriptor.length);
        sb.append(", type=").append(getDeviceTypeName());
        sb.append(", usesReportIds=").append(usesReportIds);
        sb.append(", inputReports=").append(inputReportSizes.size());
        sb.append(", outputReports=").append(outputReportSizes.size());
        sb.append(", featureReports=").append(featureReportSizes.size());
        sb.append("}");
        return sb.toString();
    }

    /**
     * Returns a detailed dump of the descriptor.
     *
     * @return detailed string representation
     */
    public String dump() {
        StringBuilder sb = new StringBuilder();
        sb.append("HID Report Descriptor (").append(rawDescriptor.length).append(" bytes)\n");
        sb.append("Device Type: ").append(getDeviceTypeName()).append("\n");
        sb.append("Uses Report IDs: ").append(usesReportIds).append("\n\n");

        // Collections
        sb.append("Collections:\n");
        for (Collection col : collections) {
            sb.append("  ").append(col).append("\n");
        }
        sb.append("\n");

        // Report sizes
        if (!inputReportSizes.isEmpty()) {
            sb.append("Input Reports:\n");
            for (Map.Entry<Integer, Integer> entry : inputReportSizes.entrySet()) {
                sb.append(String.format("  ID %d: %d bits (%d bytes)\n",
                        entry.getKey(), entry.getValue(), (entry.getValue() + 7) / 8));
            }
        }
        if (!outputReportSizes.isEmpty()) {
            sb.append("Output Reports:\n");
            for (Map.Entry<Integer, Integer> entry : outputReportSizes.entrySet()) {
                sb.append(String.format("  ID %d: %d bits (%d bytes)\n",
                        entry.getKey(), entry.getValue(), (entry.getValue() + 7) / 8));
            }
        }
        if (!featureReportSizes.isEmpty()) {
            sb.append("Feature Reports:\n");
            for (Map.Entry<Integer, Integer> entry : featureReportSizes.entrySet()) {
                sb.append(String.format("  ID %d: %d bits (%d bytes)\n",
                        entry.getKey(), entry.getValue(), (entry.getValue() + 7) / 8));
            }
        }
        sb.append("\n");

        // Fields
        if (!inputFields.isEmpty()) {
            sb.append("Input Fields:\n");
            for (ReportField field : inputFields) {
                sb.append("  ").append(field).append("\n");
            }
        }
        if (!outputFields.isEmpty()) {
            sb.append("Output Fields:\n");
            for (ReportField field : outputFields) {
                sb.append("  ").append(field).append("\n");
            }
        }
        if (!featureFields.isEmpty()) {
            sb.append("Feature Fields:\n");
            for (ReportField field : featureFields) {
                sb.append("  ").append(field).append("\n");
            }
        }

        return sb.toString();
    }

    /**
     * Returns a hex dump of the raw descriptor.
     *
     * @return hex dump string
     */
    public String hexDump() {
        StringBuilder sb = new StringBuilder();
        sb.append("Raw Descriptor:\n");
        for (int i = 0; i < rawDescriptor.length; i += 16) {
            sb.append(String.format("  %04X: ", i));
            for (int j = 0; j < 16 && i + j < rawDescriptor.length; j++) {
                sb.append(String.format("%02X ", rawDescriptor[i + j] & 0xFF));
            }
            sb.append("\n");
        }
        return sb.toString();
    }

    // ==================== Inner Classes ====================

    /**
     * Represents a field in a HID report.
     */
    public static class ReportField {
        public final int reportType;
        public final int reportId;
        public final int usagePage;
        public final int usage;
        public final int usageMin;
        public final int usageMax;
        public final int logicalMin;
        public final int logicalMax;
        public final int physicalMin;
        public final int physicalMax;
        public final int reportSize;
        public final int reportCount;
        public final int flags;
        public final int bitOffset;
        public final int unit;
        public final int unitExponent;
        private final List<Integer> usages;

        ReportField(int reportType, int reportId, int usagePage, int usage,
                    int usageMin, int usageMax, int logicalMin, int logicalMax,
                    int physicalMin, int physicalMax, int reportSize, int reportCount,
                    int flags, int bitOffset, int unit, int unitExponent) {
            this.reportType = reportType;
            this.reportId = reportId;
            this.usagePage = usagePage;
            this.usage = usage;
            this.usageMin = usageMin;
            this.usageMax = usageMax;
            this.logicalMin = logicalMin;
            this.logicalMax = logicalMax;
            this.physicalMin = physicalMin;
            this.physicalMax = physicalMax;
            this.reportSize = reportSize;
            this.reportCount = reportCount;
            this.flags = flags;
            this.bitOffset = bitOffset;
            this.unit = unit;
            this.unitExponent = unitExponent;
            this.usages = new ArrayList<>();
        }

        void addUsage(int usage) {
            usages.add(usage);
        }

        public List<Integer> getUsages() {
            return Collections.unmodifiableList(usages);
        }

        public boolean hasUsage(int usage) {
            if (usages.contains(usage)) return true;
            return usage >= usageMin && usage <= usageMax;
        }

        public int getTotalBits() {
            return reportSize * reportCount;
        }

        public int getTotalBytes() {
            return (getTotalBits() + 7) / 8;
        }

        public boolean isConstant() {
            return (flags & HidConstants.DATA_FLAG_CONSTANT) != 0;
        }

        public boolean isVariable() {
            return (flags & HidConstants.DATA_FLAG_VARIABLE) != 0;
        }

        public boolean isRelative() {
            return (flags & HidConstants.DATA_FLAG_RELATIVE) != 0;
        }

        public boolean isAbsolute() {
            return !isRelative();
        }

        public boolean isArray() {
            return !isVariable();
        }

        public boolean isWrapping() {
            return (flags & HidConstants.DATA_FLAG_WRAP) != 0;
        }

        public boolean isNonLinear() {
            return (flags & HidConstants.DATA_FLAG_NONLINEAR) != 0;
        }

        public boolean hasNoPreferred() {
            return (flags & HidConstants.DATA_FLAG_NO_PREFERRED) != 0;
        }

        public boolean hasNullState() {
            return (flags & HidConstants.DATA_FLAG_NULL_STATE) != 0;
        }

        public boolean isVolatile() {
            return (flags & HidConstants.DATA_FLAG_VOLATILE) != 0;
        }

        public boolean isBufferedBytes() {
            return (flags & HidConstants.DATA_FLAG_BUFFERED_BYTES) != 0;
        }

        /**
         * Extracts the value(s) from a report for this field.
         *
         * @param report HID report
         * @return array of values (one per reportCount)
         */
        public int[] extractValues(HidReport report) {
            int[] values = new int[reportCount];
            int offset = bitOffset;
            for (int i = 0; i < reportCount; i++) {
                if (reportSize <= 32) {
                    values[i] = report.getSigned(offset, reportSize);
                }
                offset += reportSize;
            }
            return values;
        }

        /**
         * Extracts unsigned value(s) from a report.
         *
         * @param report HID report
         * @return array of unsigned values
         */
        public long[] extractUnsignedValues(HidReport report) {
            long[] values = new long[reportCount];
            int offset = bitOffset;
            for (int i = 0; i < reportCount; i++) {
                values[i] = report.getUnsigned(offset, reportSize);
                offset += reportSize;
            }
            return values;
        }

        @Override
        public String toString() {
            StringBuilder sb = new StringBuilder();
            sb.append(HidConstants.getReportTypeName(reportType));
            if (reportId != 0) sb.append("[").append(reportId).append("]");
            sb.append(" ").append(HidConstants.getUsagePageName(usagePage));
            if (usage != 0) {
                sb.append(":0x").append(String.format("%04X", usage));
            } else if (usageMin != 0 || usageMax != 0) {
                sb.append(String.format(":0x%04X-0x%04X", usageMin, usageMax));
            }
            sb.append(" @bit").append(bitOffset);
            sb.append(" ").append(reportSize).append("b x ").append(reportCount);
            sb.append(" [").append(logicalMin).append(",").append(logicalMax).append("]");
            if (isConstant()) sb.append(" Const");
            if (isVariable()) sb.append(" Var"); else sb.append(" Array");
            if (isRelative()) sb.append(" Rel"); else sb.append(" Abs");
            return sb.toString();
        }
    }

    /**
     * Represents a collection in a HID descriptor.
     */
    public static class Collection {
        public final int type;
        public final int usagePage;
        public final int usage;
        public final Collection parent;

        Collection(int type, int usagePage, int usage, Collection parent) {
            this.type = type;
            this.usagePage = usagePage;
            this.usage = usage;
            this.parent = parent;
        }

        public String getTypeName() {
            switch (type) {
                case HidConstants.COLLECTION_PHYSICAL: return "Physical";
                case HidConstants.COLLECTION_APPLICATION: return "Application";
                case HidConstants.COLLECTION_LOGICAL: return "Logical";
                case HidConstants.COLLECTION_REPORT: return "Report";
                case HidConstants.COLLECTION_NAMED_ARRAY: return "Named Array";
                case HidConstants.COLLECTION_USAGE_SWITCH: return "Usage Switch";
                case HidConstants.COLLECTION_USAGE_MODIFIER: return "Usage Modifier";
                default:
                    if (type >= 0x80 && type <= 0xFF) return "Vendor " + type;
                    return "Reserved " + type;
            }
        }

        public boolean isApplication() {
            return type == HidConstants.COLLECTION_APPLICATION;
        }

        public boolean isPhysical() {
            return type == HidConstants.COLLECTION_PHYSICAL;
        }

        @Override
        public String toString() {
            StringBuilder sb = new StringBuilder();
            sb.append(getTypeName());
            sb.append(" (").append(HidConstants.getUsagePageName(usagePage));
            sb.append(":0x").append(String.format("%04X", usage)).append(")");
            if (usagePage == HidConstants.USAGE_PAGE_GENERIC_DESKTOP) {
                sb.append(" [").append(HidConstants.getGenericDesktopUsageName(usage)).append("]");
            }
            return sb.toString();
        }
    }

    /**
     * Represents a parsed item from the descriptor.
     */
    public static class Item {
        public final int offset;
        public final int type;
        public final int tag;
        public final byte[] data;

        Item(int offset, int type, int tag, byte[] data) {
            this.offset = offset;
            this.type = type;
            this.tag = tag;
            this.data = data.clone();
        }

        public String getTypeName() {
            switch (type) {
                case HidConstants.ITEM_TYPE_MAIN: return "Main";
                case HidConstants.ITEM_TYPE_GLOBAL: return "Global";
                case HidConstants.ITEM_TYPE_LOCAL: return "Local";
                default: return "Reserved";
            }
        }

        @Override
        public String toString() {
            StringBuilder sb = new StringBuilder();
            sb.append(String.format("@%04X ", offset));
            sb.append(getTypeName()).append("/");
            sb.append(String.format("0x%02X", tag));
            if (data.length > 0) {
                sb.append(" = ");
                for (byte b : data) {
                    sb.append(String.format("%02X", b & 0xFF));
                }
            }
            return sb.toString();
        }
    }

    /**
     * Internal parse state tracker.
     */
    private static class ParseState {
        // Global items
        int usagePage = 0;
        int logicalMin = 0;
        int logicalMax = 0;
        int physicalMin = 0;
        int physicalMax = 0;
        int unitExponent = 0;
        int unit = 0;
        int reportSize = 0;
        int reportId = 0;
        int reportCount = 0;

        // Local items
        List<Integer> usages = new ArrayList<>();
        int usageMin = 0;
        int usageMax = 0;
        int stringIndex = 0;
        int designatorIndex = 0;

        // Collection stack
        Stack<Collection> collectionStack = new Stack<>();

        // Global state stack
        Stack<int[]> globalStack = new Stack<>();

        int[] cloneGlobalState() {
            return new int[]{
                    usagePage, logicalMin, logicalMax, physicalMin, physicalMax,
                    unitExponent, unit, reportSize, reportId, reportCount
            };
        }

        void restoreGlobalState(int[] state) {
            usagePage = state[0];
            logicalMin = state[1];
            logicalMax = state[2];
            physicalMin = state[3];
            physicalMax = state[4];
            unitExponent = state[5];
            unit = state[6];
            reportSize = state[7];
            reportId = state[8];
            reportCount = state[9];
        }
    }
}
