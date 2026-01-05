package com.courierstack.hid;

/**
 * HID (Human Interface Device) protocol constants per Bluetooth HID Profile v1.1.1
 * and USB HID Specification v1.11.
 *
 * <p>This class contains all constants needed for HID protocol implementation
 * including transaction types, report types, protocol codes, result codes,
 * handshake parameters, and standard HID usage pages.
 *
 * <p>Reference:
 * <ul>
 *   <li>Bluetooth HID Profile Specification v1.1.1</li>
 *   <li>USB HID Specification v1.11</li>
 *   <li>HID Usage Tables v1.12</li>
 * </ul>
 */
public final class HidConstants {

    private HidConstants() {
        // Utility class - prevent instantiation
    }

    // ===== L2CAP PSM Values (from L2capConstants) =====

    /** HID Control channel PSM. */
    public static final int PSM_HID_CONTROL = 0x0011;

    /** HID Interrupt channel PSM. */
    public static final int PSM_HID_INTERRUPT = 0x0013;

    // ===== HID Transaction Header Format =====
    // Header byte: [Transaction Type (4 bits)][Parameter (4 bits)]

    /** Mask for transaction type in header. */
    public static final int TRANS_TYPE_MASK = 0xF0;

    /** Mask for parameter in header. */
    public static final int TRANS_PARAM_MASK = 0x0F;

    /** Shift amount for transaction type. */
    public static final int TRANS_TYPE_SHIFT = 4;

    // ===== HID Transaction Types (Section 7.3) =====

    /** HANDSHAKE - Acknowledge HID_CONTROL operation. */
    public static final int TRANS_HANDSHAKE = 0x00;

    /** HID_CONTROL - Request from host. */
    public static final int TRANS_HID_CONTROL = 0x01;

    /** Reserved transaction type. */
    public static final int TRANS_RESERVED_2 = 0x02;

    /** Reserved transaction type. */
    public static final int TRANS_RESERVED_3 = 0x03;

    /** GET_REPORT - Request report from device. */
    public static final int TRANS_GET_REPORT = 0x04;

    /** SET_REPORT - Send report to device. */
    public static final int TRANS_SET_REPORT = 0x05;

    /** GET_PROTOCOL - Get current protocol mode. */
    public static final int TRANS_GET_PROTOCOL = 0x06;

    /** SET_PROTOCOL - Set protocol mode. */
    public static final int TRANS_SET_PROTOCOL = 0x07;

    /** GET_IDLE - Get idle rate (optional). */
    public static final int TRANS_GET_IDLE = 0x08;

    /** SET_IDLE - Set idle rate (optional). */
    public static final int TRANS_SET_IDLE = 0x09;

    /** DATA - Report data on interrupt channel. */
    public static final int TRANS_DATA = 0x0A;

    /** DATC - Continuation of DATA (for fragmented reports). */
    public static final int TRANS_DATC = 0x0B;

    // ===== Handshake Result Codes (Section 7.4.1) =====

    /** Successful operation. */
    public static final int HANDSHAKE_SUCCESSFUL = 0x00;

    /** Device not ready. */
    public static final int HANDSHAKE_NOT_READY = 0x01;

    /** Invalid report ID. */
    public static final int HANDSHAKE_ERR_INVALID_REPORT_ID = 0x02;

    /** Unsupported request. */
    public static final int HANDSHAKE_ERR_UNSUPPORTED_REQUEST = 0x03;

    /** Invalid parameter. */
    public static final int HANDSHAKE_ERR_INVALID_PARAMETER = 0x04;

    /** Unknown error. */
    public static final int HANDSHAKE_ERR_UNKNOWN = 0x0E;

    /** Fatal error - device must be reset. */
    public static final int HANDSHAKE_ERR_FATAL = 0x0F;

    /** Alias for HANDSHAKE_NOT_READY for convenience. */
    public static final int HANDSHAKE_ERR_NOT_READY = HANDSHAKE_NOT_READY;

    // ===== HID_CONTROL Parameters (Section 7.4.2) =====

    /** No operation / reserved. */
    public static final int CTRL_NOP = 0x00;

    /** Hard reset - device returns to power-on state. */
    public static final int CTRL_HARD_RESET = 0x01;

    /** Soft reset - device returns to initialized state. */
    public static final int CTRL_SOFT_RESET = 0x02;

    /** Suspend - device enters low power mode. */
    public static final int CTRL_SUSPEND = 0x03;

    /** Exit suspend - device returns to active mode. */
    public static final int CTRL_EXIT_SUSPEND = 0x04;

    /** Virtual cable unplug - terminate connection. */
    public static final int CTRL_VIRTUAL_CABLE_UNPLUG = 0x05;

    // ===== Report Types (Section 7.4.3, 7.4.4) =====

    /** Reserved report type. */
    public static final int REPORT_TYPE_RESERVED = 0x00;

    /** Input report - data from device to host. */
    public static final int REPORT_TYPE_INPUT = 0x01;

    /** Output report - data from host to device. */
    public static final int REPORT_TYPE_OUTPUT = 0x02;

    /** Feature report - bidirectional configuration data. */
    public static final int REPORT_TYPE_FEATURE = 0x03;

    // ===== Protocol Mode Values (Section 7.4.5, 7.4.6) =====

    /** Boot protocol mode. */
    public static final int PROTOCOL_BOOT = 0x00;

    /** Report protocol mode (full HID). */
    public static final int PROTOCOL_REPORT = 0x01;

    // ===== Boot Protocol Subclass Codes (Section 4.2) =====

    /** No subclass. */
    public static final int SUBCLASS_NONE = 0x00;

    /** Boot interface subclass. */
    public static final int SUBCLASS_BOOT_INTERFACE = 0x01;

    // ===== Boot Protocol Codes (Section 4.3) =====

    /** No boot protocol. */
    public static final int BOOT_PROTOCOL_NONE = 0x00;

    /** Keyboard boot protocol. */
    public static final int BOOT_PROTOCOL_KEYBOARD = 0x01;

    /** Mouse boot protocol. */
    public static final int BOOT_PROTOCOL_MOUSE = 0x02;

    // ===== HID Descriptor Types (USB HID Spec) =====

    /** HID descriptor type. */
    public static final int DESC_TYPE_HID = 0x21;

    /** Report descriptor type. */
    public static final int DESC_TYPE_REPORT = 0x22;

    /** Physical descriptor type. */
    public static final int DESC_TYPE_PHYSICAL = 0x23;

    // ===== HID Report Descriptor Item Types =====

    /** Main item type. */
    public static final int ITEM_TYPE_MAIN = 0x00;

    /** Global item type. */
    public static final int ITEM_TYPE_GLOBAL = 0x01;

    /** Local item type. */
    public static final int ITEM_TYPE_LOCAL = 0x02;

    /** Reserved/Long item type. */
    public static final int ITEM_TYPE_RESERVED = 0x03;

    // ===== Main Item Tags =====

    /** Input item. */
    public static final int MAIN_INPUT = 0x08;

    /** Output item. */
    public static final int MAIN_OUTPUT = 0x09;

    /** Feature item. */
    public static final int MAIN_FEATURE = 0x0B;

    /** Collection item. */
    public static final int MAIN_COLLECTION = 0x0A;

    /** End collection item. */
    public static final int MAIN_END_COLLECTION = 0x0C;

    // ===== Global Item Tags =====

    /** Usage Page. */
    public static final int GLOBAL_USAGE_PAGE = 0x00;

    /** Logical Minimum. */
    public static final int GLOBAL_LOGICAL_MIN = 0x01;

    /** Logical Maximum. */
    public static final int GLOBAL_LOGICAL_MAX = 0x02;

    /** Physical Minimum. */
    public static final int GLOBAL_PHYSICAL_MIN = 0x03;

    /** Physical Maximum. */
    public static final int GLOBAL_PHYSICAL_MAX = 0x04;

    /** Unit Exponent. */
    public static final int GLOBAL_UNIT_EXPONENT = 0x05;

    /** Unit. */
    public static final int GLOBAL_UNIT = 0x06;

    /** Report Size (bits per field). */
    public static final int GLOBAL_REPORT_SIZE = 0x07;

    /** Report ID. */
    public static final int GLOBAL_REPORT_ID = 0x08;

    /** Report Count (number of fields). */
    public static final int GLOBAL_REPORT_COUNT = 0x09;

    /** Push global state. */
    public static final int GLOBAL_PUSH = 0x0A;

    /** Pop global state. */
    public static final int GLOBAL_POP = 0x0B;

    // ===== Local Item Tags =====

    /** Usage. */
    public static final int LOCAL_USAGE = 0x00;

    /** Usage Minimum. */
    public static final int LOCAL_USAGE_MIN = 0x01;

    /** Usage Maximum. */
    public static final int LOCAL_USAGE_MAX = 0x02;

    /** Designator Index. */
    public static final int LOCAL_DESIGNATOR_INDEX = 0x03;

    /** Designator Minimum. */
    public static final int LOCAL_DESIGNATOR_MIN = 0x04;

    /** Designator Maximum. */
    public static final int LOCAL_DESIGNATOR_MAX = 0x05;

    /** String Index. */
    public static final int LOCAL_STRING_INDEX = 0x07;

    /** String Minimum. */
    public static final int LOCAL_STRING_MIN = 0x08;

    /** String Maximum. */
    public static final int LOCAL_STRING_MAX = 0x09;

    /** Delimiter. */
    public static final int LOCAL_DELIMITER = 0x0A;

    // ===== Collection Types =====

    /** Physical collection (group of axes). */
    public static final int COLLECTION_PHYSICAL = 0x00;

    /** Application collection (mouse, keyboard, etc.). */
    public static final int COLLECTION_APPLICATION = 0x01;

    /** Logical collection (interrelated data). */
    public static final int COLLECTION_LOGICAL = 0x02;

    /** Report collection. */
    public static final int COLLECTION_REPORT = 0x03;

    /** Named array collection. */
    public static final int COLLECTION_NAMED_ARRAY = 0x04;

    /** Usage switch collection. */
    public static final int COLLECTION_USAGE_SWITCH = 0x05;

    /** Usage modifier collection. */
    public static final int COLLECTION_USAGE_MODIFIER = 0x06;

    // ===== Input/Output/Feature Data Flags =====

    /** Data (0) vs Constant (1). */
    public static final int DATA_FLAG_CONSTANT = 0x01;

    /** Array (0) vs Variable (1). */
    public static final int DATA_FLAG_VARIABLE = 0x02;

    /** Absolute (0) vs Relative (1). */
    public static final int DATA_FLAG_RELATIVE = 0x04;

    /** No Wrap (0) vs Wrap (1). */
    public static final int DATA_FLAG_WRAP = 0x08;

    /** Linear (0) vs Non-Linear (1). */
    public static final int DATA_FLAG_NONLINEAR = 0x10;

    /** Preferred State (0) vs No Preferred (1). */
    public static final int DATA_FLAG_NO_PREFERRED = 0x20;

    /** No Null position (0) vs Null state (1). */
    public static final int DATA_FLAG_NULL_STATE = 0x40;

    /** Non-Volatile (0) vs Volatile (1) - for feature/output only. */
    public static final int DATA_FLAG_VOLATILE = 0x80;

    /** Bit Field (0) vs Buffered Bytes (1). */
    public static final int DATA_FLAG_BUFFERED_BYTES = 0x100;

    // ===== Usage Pages (HID Usage Tables) =====

    /** Generic Desktop Page (0x01). */
    public static final int USAGE_PAGE_GENERIC_DESKTOP = 0x01;

    /** Simulation Controls Page (0x02). */
    public static final int USAGE_PAGE_SIMULATION = 0x02;

    /** VR Controls Page (0x03). */
    public static final int USAGE_PAGE_VR = 0x03;

    /** Sport Controls Page (0x04). */
    public static final int USAGE_PAGE_SPORT = 0x04;

    /** Game Controls Page (0x05). */
    public static final int USAGE_PAGE_GAME = 0x05;

    /** Generic Device Controls Page (0x06). */
    public static final int USAGE_PAGE_GENERIC_DEVICE = 0x06;

    /** Keyboard/Keypad Page (0x07). */
    public static final int USAGE_PAGE_KEYBOARD = 0x07;

    /** LED Page (0x08). */
    public static final int USAGE_PAGE_LED = 0x08;

    /** Button Page (0x09). */
    public static final int USAGE_PAGE_BUTTON = 0x09;

    /** Ordinal Page (0x0A). */
    public static final int USAGE_PAGE_ORDINAL = 0x0A;

    /** Telephony Device Page (0x0B). */
    public static final int USAGE_PAGE_TELEPHONY = 0x0B;

    /** Consumer Page (0x0C). */
    public static final int USAGE_PAGE_CONSUMER = 0x0C;

    /** Digitizers Page (0x0D). */
    public static final int USAGE_PAGE_DIGITIZER = 0x0D;

    /** Haptics Page (0x0E). */
    public static final int USAGE_PAGE_HAPTICS = 0x0E;

    /** Physical Input Device Page (0x0F). */
    public static final int USAGE_PAGE_PID = 0x0F;

    /** Unicode Page (0x10). */
    public static final int USAGE_PAGE_UNICODE = 0x10;

    /** Eye and Head Trackers Page (0x12). */
    public static final int USAGE_PAGE_EYE_HEAD_TRACKER = 0x12;

    /** Auxiliary Display Page (0x14). */
    public static final int USAGE_PAGE_AUX_DISPLAY = 0x14;

    /** Sensors Page (0x20). */
    public static final int USAGE_PAGE_SENSORS = 0x20;

    /** Medical Instrument Page (0x40). */
    public static final int USAGE_PAGE_MEDICAL = 0x40;

    /** Braille Display Page (0x41). */
    public static final int USAGE_PAGE_BRAILLE = 0x41;

    /** Lighting and Illumination Page (0x59). */
    public static final int USAGE_PAGE_LIGHTING = 0x59;

    /** Monitor Page (0x80). */
    public static final int USAGE_PAGE_MONITOR = 0x80;

    /** Monitor Enumerated Page (0x81). */
    public static final int USAGE_PAGE_MONITOR_ENUM = 0x81;

    /** VESA Virtual Controls Page (0x82). */
    public static final int USAGE_PAGE_VESA_VC = 0x82;

    /** Power Page (0x84). */
    public static final int USAGE_PAGE_POWER = 0x84;

    /** Battery System Page (0x85). */
    public static final int USAGE_PAGE_BATTERY = 0x85;

    /** Barcode Scanner Page (0x8C). */
    public static final int USAGE_PAGE_BARCODE = 0x8C;

    /** Scale Page (0x8D). */
    public static final int USAGE_PAGE_SCALE = 0x8D;

    /** Magnetic Stripe Reader Page (0x8E). */
    public static final int USAGE_PAGE_MSR = 0x8E;

    /** Camera Control Page (0x90). */
    public static final int USAGE_PAGE_CAMERA = 0x90;

    /** Arcade Page (0x91). */
    public static final int USAGE_PAGE_ARCADE = 0x91;

    /** Gaming Device Page (0x92). */
    public static final int USAGE_PAGE_GAMING_DEVICE = 0x92;

    /** FIDO Alliance Page (0xF1D0). */
    public static final int USAGE_PAGE_FIDO = 0xF1D0;

    /** Vendor-defined page start (0xFF00). */
    public static final int USAGE_PAGE_VENDOR_START = 0xFF00;

    /** Vendor-defined page end (0xFFFF). */
    public static final int USAGE_PAGE_VENDOR_END = 0xFFFF;

    // ===== Generic Desktop Usage IDs =====

    /** Undefined usage. */
    public static final int USAGE_UNDEFINED = 0x00;

    /** Pointer usage. */
    public static final int USAGE_POINTER = 0x01;

    /** Mouse usage. */
    public static final int USAGE_MOUSE = 0x02;

    /** Reserved. */
    public static final int USAGE_RESERVED_3 = 0x03;

    /** Joystick usage. */
    public static final int USAGE_JOYSTICK = 0x04;

    /** Gamepad usage. */
    public static final int USAGE_GAMEPAD = 0x05;

    /** Keyboard usage. */
    public static final int USAGE_KEYBOARD = 0x06;

    /** Keypad usage. */
    public static final int USAGE_KEYPAD = 0x07;

    /** Multi-axis Controller usage. */
    public static final int USAGE_MULTI_AXIS_CONTROLLER = 0x08;

    /** Tablet PC System Controls. */
    public static final int USAGE_TABLET_PC = 0x09;

    /** Water Cooling Device. */
    public static final int USAGE_WATER_COOLING = 0x0A;

    /** Computer Chassis Device. */
    public static final int USAGE_CHASSIS = 0x0B;

    /** Wireless Radio Controls. */
    public static final int USAGE_WIRELESS_RADIO = 0x0C;

    /** Portable Device Control. */
    public static final int USAGE_PORTABLE_DEVICE = 0x0D;

    /** System Multi-Axis Controller. */
    public static final int USAGE_SYSTEM_MULTI_AXIS = 0x0E;

    /** Spatial Controller. */
    public static final int USAGE_SPATIAL = 0x0F;

    /** Assistive Control. */
    public static final int USAGE_ASSISTIVE = 0x10;

    /** Device Dock. */
    public static final int USAGE_DEVICE_DOCK = 0x11;

    /** Dockable Device. */
    public static final int USAGE_DOCKABLE_DEVICE = 0x12;

    /** X axis. */
    public static final int USAGE_X = 0x30;

    /** Y axis. */
    public static final int USAGE_Y = 0x31;

    /** Z axis. */
    public static final int USAGE_Z = 0x32;

    /** X rotation. */
    public static final int USAGE_RX = 0x33;

    /** Y rotation. */
    public static final int USAGE_RY = 0x34;

    /** Z rotation. */
    public static final int USAGE_RZ = 0x35;

    /** Slider. */
    public static final int USAGE_SLIDER = 0x36;

    /** Dial. */
    public static final int USAGE_DIAL = 0x37;

    /** Wheel. */
    public static final int USAGE_WHEEL = 0x38;

    /** Hat switch. */
    public static final int USAGE_HAT_SWITCH = 0x39;

    /** Counted buffer. */
    public static final int USAGE_COUNTED_BUFFER = 0x3A;

    /** Byte count. */
    public static final int USAGE_BYTE_COUNT = 0x3B;

    /** Motion wakeup. */
    public static final int USAGE_MOTION_WAKEUP = 0x3C;

    /** Start. */
    public static final int USAGE_START = 0x3D;

    /** Select. */
    public static final int USAGE_SELECT = 0x3E;

    /** Vx. */
    public static final int USAGE_VX = 0x40;

    /** Vy. */
    public static final int USAGE_VY = 0x41;

    /** Vz. */
    public static final int USAGE_VZ = 0x42;

    /** Vbrx. */
    public static final int USAGE_VBRX = 0x43;

    /** Vbry. */
    public static final int USAGE_VBRY = 0x44;

    /** Vbrz. */
    public static final int USAGE_VBRZ = 0x45;

    /** Vno. */
    public static final int USAGE_VNO = 0x46;

    /** Feature Notification. */
    public static final int USAGE_FEATURE_NOTIFICATION = 0x47;

    /** Resolution Multiplier. */
    public static final int USAGE_RESOLUTION_MULTIPLIER = 0x48;

    /** Horizontal scroll (high resolution wheel). */
    public static final int USAGE_HORIZONTAL_WHEEL = 0x0238;

    // ===== System Control Usage IDs (0x80-0x9F) =====

    /** System Control. */
    public static final int USAGE_SYSTEM_CONTROL = 0x80;

    /** System Power Down. */
    public static final int USAGE_SYSTEM_POWER_DOWN = 0x81;

    /** System Sleep. */
    public static final int USAGE_SYSTEM_SLEEP = 0x82;

    /** System Wake Up. */
    public static final int USAGE_SYSTEM_WAKE_UP = 0x83;

    /** System Context Menu. */
    public static final int USAGE_SYSTEM_CONTEXT_MENU = 0x84;

    /** System Main Menu. */
    public static final int USAGE_SYSTEM_MAIN_MENU = 0x85;

    /** System App Menu. */
    public static final int USAGE_SYSTEM_APP_MENU = 0x86;

    /** System Menu Help. */
    public static final int USAGE_SYSTEM_MENU_HELP = 0x87;

    /** System Menu Exit. */
    public static final int USAGE_SYSTEM_MENU_EXIT = 0x88;

    /** System Menu Select. */
    public static final int USAGE_SYSTEM_MENU_SELECT = 0x89;

    /** System Menu Right. */
    public static final int USAGE_SYSTEM_MENU_RIGHT = 0x8A;

    /** System Menu Left. */
    public static final int USAGE_SYSTEM_MENU_LEFT = 0x8B;

    /** System Menu Up. */
    public static final int USAGE_SYSTEM_MENU_UP = 0x8C;

    /** System Menu Down. */
    public static final int USAGE_SYSTEM_MENU_DOWN = 0x8D;

    // ===== D-pad Usage IDs (0x90-0x93) =====

    /** D-pad Up. */
    public static final int USAGE_DPAD_UP = 0x90;

    /** D-pad Down. */
    public static final int USAGE_DPAD_DOWN = 0x91;

    /** D-pad Right. */
    public static final int USAGE_DPAD_RIGHT = 0x92;

    /** D-pad Left. */
    public static final int USAGE_DPAD_LEFT = 0x93;

    // ===== Boot Protocol Report Sizes =====

    /** Boot keyboard input report size (8 bytes). */
    public static final int BOOT_KEYBOARD_INPUT_SIZE = 8;

    /** Boot keyboard output report size (1 byte). */
    public static final int BOOT_KEYBOARD_OUTPUT_SIZE = 1;

    /** Boot mouse input report size (3 bytes minimum). */
    public static final int BOOT_MOUSE_INPUT_SIZE = 3;

    // ===== Boot Keyboard Modifier Bits =====

    /** Left Control modifier. */
    public static final int MOD_LEFT_CTRL = 0x01;

    /** Left Shift modifier. */
    public static final int MOD_LEFT_SHIFT = 0x02;

    /** Left Alt modifier. */
    public static final int MOD_LEFT_ALT = 0x04;

    /** Left GUI (Windows/Command) modifier. */
    public static final int MOD_LEFT_GUI = 0x08;

    /** Right Control modifier. */
    public static final int MOD_RIGHT_CTRL = 0x10;

    /** Right Shift modifier. */
    public static final int MOD_RIGHT_SHIFT = 0x20;

    /** Right Alt modifier. */
    public static final int MOD_RIGHT_ALT = 0x40;

    /** Right GUI (Windows/Command) modifier. */
    public static final int MOD_RIGHT_GUI = 0x80;

    // ===== Boot Keyboard LED Bits =====

    /** Num Lock LED. */
    public static final int LED_NUM_LOCK = 0x01;

    /** Caps Lock LED. */
    public static final int LED_CAPS_LOCK = 0x02;

    /** Scroll Lock LED. */
    public static final int LED_SCROLL_LOCK = 0x04;

    /** Compose LED. */
    public static final int LED_COMPOSE = 0x08;

    /** Kana LED. */
    public static final int LED_KANA = 0x10;

    // ===== Boot Mouse Button Bits =====

    /** Left button. */
    public static final int BUTTON_LEFT = 0x01;

    /** Right button. */
    public static final int BUTTON_RIGHT = 0x02;

    /** Middle button. */
    public static final int BUTTON_MIDDLE = 0x04;

    // ===== Timeouts and Limits =====

    /** Default timeout for HID operations (ms). */
    public static final int DEFAULT_TIMEOUT_MS = 5000;

    /** Maximum HID report size (bytes). */
    public static final int MAX_REPORT_SIZE = 65535;

    /** Maximum HID descriptor size (bytes). */
    public static final int MAX_DESCRIPTOR_SIZE = 4096;

    /** Maximum number of report IDs. */
    public static final int MAX_REPORT_IDS = 256;

    /** Idle rate default (indefinite). */
    public static final int IDLE_RATE_INDEFINITE = 0;

    // ===== SDP Attribute IDs for HID =====

    /** HID Device Release Number. */
    public static final int SDP_ATTR_HID_DEVICE_RELEASE = 0x0200;

    /** HID Parser Version. */
    public static final int SDP_ATTR_HID_PARSER_VERSION = 0x0201;

    /** HID Device Subclass. */
    public static final int SDP_ATTR_HID_DEVICE_SUBCLASS = 0x0202;

    /** HID Country Code. */
    public static final int SDP_ATTR_HID_COUNTRY_CODE = 0x0203;

    /** HID Virtual Cable. */
    public static final int SDP_ATTR_HID_VIRTUAL_CABLE = 0x0204;

    /** HID Reconnect Initiate. */
    public static final int SDP_ATTR_HID_RECONNECT_INITIATE = 0x0205;

    /** HID Descriptor List. */
    public static final int SDP_ATTR_HID_DESCRIPTOR_LIST = 0x0206;

    /** HID LANGID Base List. */
    public static final int SDP_ATTR_HID_LANGID_BASE_LIST = 0x0207;

    /** HID SDP Disable. */
    public static final int SDP_ATTR_HID_SDP_DISABLE = 0x0208;

    /** HID Battery Power. */
    public static final int SDP_ATTR_HID_BATTERY_POWER = 0x0209;

    /** HID Remote Wake. */
    public static final int SDP_ATTR_HID_REMOTE_WAKE = 0x020A;

    /** HID Profile Version. */
    public static final int SDP_ATTR_HID_PROFILE_VERSION = 0x020B;

    /** HID Supervision Timeout. */
    public static final int SDP_ATTR_HID_SUPERVISION_TIMEOUT = 0x020C;

    /** HID Normally Connectable. */
    public static final int SDP_ATTR_HID_NORMALLY_CONNECTABLE = 0x020D;

    /** HID Boot Device. */
    public static final int SDP_ATTR_HID_BOOT_DEVICE = 0x020E;

    /** HID SSR Host Max Latency. */
    public static final int SDP_ATTR_HID_SSR_HOST_MAX_LATENCY = 0x020F;

    /** HID SSR Host Min Timeout. */
    public static final int SDP_ATTR_HID_SSR_HOST_MIN_TIMEOUT = 0x0210;

    // ===== Device Types =====

    /** Unknown device type. */
    public static final int DEVICE_TYPE_UNKNOWN = 0;

    /** Keyboard device. */
    public static final int DEVICE_TYPE_KEYBOARD = 1;

    /** Mouse device. */
    public static final int DEVICE_TYPE_MOUSE = 2;

    /** Keyboard and mouse combo device. */
    public static final int DEVICE_TYPE_KEYBOARD_MOUSE_COMBO = 3;

    /** Gamepad device. */
    public static final int DEVICE_TYPE_GAMEPAD = 4;

    /** Joystick device. */
    public static final int DEVICE_TYPE_JOYSTICK = 5;

    /** Remote control device. */
    public static final int DEVICE_TYPE_REMOTE = 6;

    /** Digitizer/Touchpad device. */
    public static final int DEVICE_TYPE_DIGITIZER = 7;

    /** Barcode scanner device. */
    public static final int DEVICE_TYPE_BARCODE_SCANNER = 8;

    // ===== Utility Methods =====

    /**
     * Creates a HID transaction header byte.
     *
     * @param transactionType transaction type (0x00-0x0B)
     * @param parameter       parameter (0x00-0x0F)
     * @return header byte
     */
    public static byte createHeader(int transactionType, int parameter) {
        return (byte) (((transactionType & 0x0F) << TRANS_TYPE_SHIFT) | (parameter & 0x0F));
    }

    /**
     * Extracts the transaction type from a header byte.
     *
     * @param header header byte
     * @return transaction type (0x00-0x0F)
     */
    public static int getTransactionType(byte header) {
        return (header >> TRANS_TYPE_SHIFT) & 0x0F;
    }

    /**
     * Extracts the parameter from a header byte.
     *
     * @param header header byte
     * @return parameter (0x00-0x0F)
     */
    public static int getParameter(byte header) {
        return header & TRANS_PARAM_MASK;
    }

    /**
     * Extracts the transaction type from a header (int version).
     *
     * @param header header value
     * @return transaction type (0x00-0x0F)
     */
    public static int getTransactionType(int header) {
        return (header >> TRANS_TYPE_SHIFT) & 0x0F;
    }

    /**
     * Extracts the parameter from a header (int version).
     *
     * @param header header value
     * @return parameter (0x00-0x0F)
     */
    public static int getParameter(int header) {
        return header & TRANS_PARAM_MASK;
    }

    /**
     * Returns a human-readable name for a transaction type.
     *
     * @param type transaction type
     * @return transaction name
     */
    public static String getTransactionName(int type) {
        switch (type) {
            case TRANS_HANDSHAKE: return "HANDSHAKE";
            case TRANS_HID_CONTROL: return "HID_CONTROL";
            case TRANS_GET_REPORT: return "GET_REPORT";
            case TRANS_SET_REPORT: return "SET_REPORT";
            case TRANS_GET_PROTOCOL: return "GET_PROTOCOL";
            case TRANS_SET_PROTOCOL: return "SET_PROTOCOL";
            case TRANS_GET_IDLE: return "GET_IDLE";
            case TRANS_SET_IDLE: return "SET_IDLE";
            case TRANS_DATA: return "DATA";
            case TRANS_DATC: return "DATC";
            default: return String.format("UNKNOWN(0x%02X)", type);
        }
    }

    /**
     * Alias for getTransactionName.
     *
     * @param type transaction type
     * @return transaction name
     */
    public static String getTransactionTypeName(int type) {
        return getTransactionName(type);
    }

    /**
     * Returns a human-readable name for a handshake result.
     *
     * @param result handshake result code
     * @return result name
     */
    public static String getHandshakeName(int result) {
        switch (result) {
            case HANDSHAKE_SUCCESSFUL: return "SUCCESSFUL";
            case HANDSHAKE_NOT_READY: return "NOT_READY";
            case HANDSHAKE_ERR_INVALID_REPORT_ID: return "ERR_INVALID_REPORT_ID";
            case HANDSHAKE_ERR_UNSUPPORTED_REQUEST: return "ERR_UNSUPPORTED_REQUEST";
            case HANDSHAKE_ERR_INVALID_PARAMETER: return "ERR_INVALID_PARAMETER";
            case HANDSHAKE_ERR_UNKNOWN: return "ERR_UNKNOWN";
            case HANDSHAKE_ERR_FATAL: return "ERR_FATAL";
            default: return String.format("UNKNOWN(0x%02X)", result);
        }
    }

    /**
     * Returns a human-readable name for a HID_CONTROL parameter.
     *
     * @param param control parameter
     * @return parameter name
     */
    public static String getControlName(int param) {
        switch (param) {
            case CTRL_NOP: return "NOP";
            case CTRL_HARD_RESET: return "HARD_RESET";
            case CTRL_SOFT_RESET: return "SOFT_RESET";
            case CTRL_SUSPEND: return "SUSPEND";
            case CTRL_EXIT_SUSPEND: return "EXIT_SUSPEND";
            case CTRL_VIRTUAL_CABLE_UNPLUG: return "VIRTUAL_CABLE_UNPLUG";
            default: return String.format("UNKNOWN(0x%02X)", param);
        }
    }

    /**
     * Returns a human-readable name for a report type.
     *
     * @param type report type
     * @return type name
     */
    public static String getReportTypeName(int type) {
        switch (type) {
            case REPORT_TYPE_INPUT: return "INPUT";
            case REPORT_TYPE_OUTPUT: return "OUTPUT";
            case REPORT_TYPE_FEATURE: return "FEATURE";
            default: return String.format("RESERVED(0x%02X)", type);
        }
    }

    /**
     * Returns a human-readable name for a protocol mode.
     *
     * @param protocol protocol mode
     * @return protocol name
     */
    public static String getProtocolName(int protocol) {
        switch (protocol) {
            case PROTOCOL_BOOT: return "BOOT";
            case PROTOCOL_REPORT: return "REPORT";
            default: return String.format("UNKNOWN(0x%02X)", protocol);
        }
    }

    /**
     * Returns a human-readable name for a usage page.
     *
     * @param page usage page
     * @return page name
     */
    public static String getUsagePageName(int page) {
        switch (page) {
            case USAGE_PAGE_GENERIC_DESKTOP: return "Generic Desktop";
            case USAGE_PAGE_SIMULATION: return "Simulation Controls";
            case USAGE_PAGE_VR: return "VR Controls";
            case USAGE_PAGE_SPORT: return "Sport Controls";
            case USAGE_PAGE_GAME: return "Game Controls";
            case USAGE_PAGE_GENERIC_DEVICE: return "Generic Device Controls";
            case USAGE_PAGE_KEYBOARD: return "Keyboard/Keypad";
            case USAGE_PAGE_LED: return "LED";
            case USAGE_PAGE_BUTTON: return "Button";
            case USAGE_PAGE_ORDINAL: return "Ordinal";
            case USAGE_PAGE_TELEPHONY: return "Telephony Device";
            case USAGE_PAGE_CONSUMER: return "Consumer";
            case USAGE_PAGE_DIGITIZER: return "Digitizers";
            case USAGE_PAGE_HAPTICS: return "Haptics";
            case USAGE_PAGE_PID: return "Physical Input Device";
            case USAGE_PAGE_UNICODE: return "Unicode";
            case USAGE_PAGE_EYE_HEAD_TRACKER: return "Eye/Head Trackers";
            case USAGE_PAGE_AUX_DISPLAY: return "Auxiliary Display";
            case USAGE_PAGE_SENSORS: return "Sensors";
            case USAGE_PAGE_MEDICAL: return "Medical Instrument";
            case USAGE_PAGE_BRAILLE: return "Braille Display";
            case USAGE_PAGE_LIGHTING: return "Lighting and Illumination";
            case USAGE_PAGE_MONITOR: return "Monitor";
            case USAGE_PAGE_POWER: return "Power";
            case USAGE_PAGE_BATTERY: return "Battery System";
            case USAGE_PAGE_BARCODE: return "Barcode Scanner";
            case USAGE_PAGE_CAMERA: return "Camera Control";
            case USAGE_PAGE_ARCADE: return "Arcade";
            case USAGE_PAGE_GAMING_DEVICE: return "Gaming Device";
            case USAGE_PAGE_FIDO: return "FIDO Alliance";
            default:
                if (page >= USAGE_PAGE_VENDOR_START && page <= USAGE_PAGE_VENDOR_END) {
                    return String.format("Vendor Defined (0x%04X)", page);
                }
                return String.format("Unknown (0x%04X)", page);
        }
    }

    /**
     * Returns a human-readable name for a Generic Desktop usage.
     *
     * @param usage usage ID
     * @return usage name
     */
    public static String getGenericDesktopUsageName(int usage) {
        switch (usage) {
            case USAGE_POINTER: return "Pointer";
            case USAGE_MOUSE: return "Mouse";
            case USAGE_JOYSTICK: return "Joystick";
            case USAGE_GAMEPAD: return "Gamepad";
            case USAGE_KEYBOARD: return "Keyboard";
            case USAGE_KEYPAD: return "Keypad";
            case USAGE_MULTI_AXIS_CONTROLLER: return "Multi-axis Controller";
            case USAGE_TABLET_PC: return "Tablet PC";
            case USAGE_X: return "X";
            case USAGE_Y: return "Y";
            case USAGE_Z: return "Z";
            case USAGE_RX: return "Rx";
            case USAGE_RY: return "Ry";
            case USAGE_RZ: return "Rz";
            case USAGE_SLIDER: return "Slider";
            case USAGE_DIAL: return "Dial";
            case USAGE_WHEEL: return "Wheel";
            case USAGE_HAT_SWITCH: return "Hat Switch";
            case USAGE_SYSTEM_CONTROL: return "System Control";
            case USAGE_SYSTEM_POWER_DOWN: return "System Power Down";
            case USAGE_SYSTEM_SLEEP: return "System Sleep";
            case USAGE_SYSTEM_WAKE_UP: return "System Wake Up";
            case USAGE_DPAD_UP: return "D-pad Up";
            case USAGE_DPAD_DOWN: return "D-pad Down";
            case USAGE_DPAD_RIGHT: return "D-pad Right";
            case USAGE_DPAD_LEFT: return "D-pad Left";
            default: return String.format("Usage 0x%04X", usage);
        }
    }

    /**
     * Returns a human-readable name for a device type.
     *
     * @param type device type
     * @return type name
     */
    public static String getDeviceTypeName(int type) {
        switch (type) {
            case DEVICE_TYPE_KEYBOARD: return "Keyboard";
            case DEVICE_TYPE_MOUSE: return "Mouse";
            case DEVICE_TYPE_KEYBOARD_MOUSE_COMBO: return "Keyboard+Mouse Combo";
            case DEVICE_TYPE_GAMEPAD: return "Gamepad";
            case DEVICE_TYPE_JOYSTICK: return "Joystick";
            case DEVICE_TYPE_REMOTE: return "Remote Control";
            case DEVICE_TYPE_DIGITIZER: return "Digitizer/Touchpad";
            case DEVICE_TYPE_BARCODE_SCANNER: return "Barcode Scanner";
            default: return "Unknown";
        }
    }

    /**
     * Determines device type from boot protocol code.
     *
     * @param bootProtocol boot protocol code
     * @return device type constant
     */
    public static int deviceTypeFromBootProtocol(int bootProtocol) {
        switch (bootProtocol) {
            case BOOT_PROTOCOL_KEYBOARD: return DEVICE_TYPE_KEYBOARD;
            case BOOT_PROTOCOL_MOUSE: return DEVICE_TYPE_MOUSE;
            default: return DEVICE_TYPE_UNKNOWN;
        }
    }

    /**
     * Checks if a report type is valid.
     *
     * @param type report type
     * @return true if valid
     */
    public static boolean isValidReportType(int type) {
        return type == REPORT_TYPE_INPUT ||
                type == REPORT_TYPE_OUTPUT ||
                type == REPORT_TYPE_FEATURE;
    }

    /**
     * Checks if a protocol mode is valid.
     *
     * @param protocol protocol mode
     * @return true if valid
     */
    public static boolean isValidProtocol(int protocol) {
        return protocol == PROTOCOL_BOOT || protocol == PROTOCOL_REPORT;
    }

    /**
     * Formats modifier byte as string.
     *
     * @param modifiers modifier byte from keyboard report
     * @return human-readable modifier string
     */
    public static String formatModifiers(int modifiers) {
        StringBuilder sb = new StringBuilder();
        if ((modifiers & MOD_LEFT_CTRL) != 0) sb.append("LCtrl ");
        if ((modifiers & MOD_LEFT_SHIFT) != 0) sb.append("LShift ");
        if ((modifiers & MOD_LEFT_ALT) != 0) sb.append("LAlt ");
        if ((modifiers & MOD_LEFT_GUI) != 0) sb.append("LGui ");
        if ((modifiers & MOD_RIGHT_CTRL) != 0) sb.append("RCtrl ");
        if ((modifiers & MOD_RIGHT_SHIFT) != 0) sb.append("RShift ");
        if ((modifiers & MOD_RIGHT_ALT) != 0) sb.append("RAlt ");
        if ((modifiers & MOD_RIGHT_GUI) != 0) sb.append("RGui ");
        return sb.toString().trim();
    }

    /**
     * Formats LED byte as string.
     *
     * @param leds LED byte
     * @return human-readable LED string
     */
    public static String formatLeds(int leds) {
        StringBuilder sb = new StringBuilder();
        if ((leds & LED_NUM_LOCK) != 0) sb.append("NumLock ");
        if ((leds & LED_CAPS_LOCK) != 0) sb.append("CapsLock ");
        if ((leds & LED_SCROLL_LOCK) != 0) sb.append("ScrollLock ");
        if ((leds & LED_COMPOSE) != 0) sb.append("Compose ");
        if ((leds & LED_KANA) != 0) sb.append("Kana ");
        return sb.toString().trim();
    }

    /**
     * Formats mouse buttons as string.
     *
     * @param buttons button byte
     * @return human-readable button string
     */
    public static String formatButtons(int buttons) {
        StringBuilder sb = new StringBuilder();
        if ((buttons & BUTTON_LEFT) != 0) sb.append("Left ");
        if ((buttons & BUTTON_RIGHT) != 0) sb.append("Right ");
        if ((buttons & BUTTON_MIDDLE) != 0) sb.append("Middle ");
        // Additional buttons
        for (int i = 3; i < 8; i++) {
            if ((buttons & (1 << i)) != 0) {
                sb.append("Button").append(i + 1).append(" ");
            }
        }
        return sb.toString().trim();
    }
}