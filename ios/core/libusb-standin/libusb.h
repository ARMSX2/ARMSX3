// SPDX-License-Identifier: GPL-2.0-only
// A header-only stand-in for the part of <libusb.h> upstream's USB bus uses: USB 2.0 spec values, no hardware reached.

#pragma once

#include <stddef.h>
#include <stdint.h>

#define LIBUSB_CALL
// Below 0x01000102, so sys_usbd.cpp builds no hotplug code and rescans an empty bus.
#define LIBUSB_API_VERSION 0x01000100

struct timeval;

typedef struct libusb_context libusb_context;
typedef struct libusb_device libusb_device;
typedef struct libusb_device_handle libusb_device_handle;

enum libusb_error
{
	LIBUSB_SUCCESS = 0,
	LIBUSB_ERROR_BUSY = -6,
	LIBUSB_ERROR_NOT_SUPPORTED = -12,
};

enum libusb_transfer_status
{
	LIBUSB_TRANSFER_COMPLETED = 0,
	LIBUSB_TRANSFER_ERROR = 1,
	LIBUSB_TRANSFER_TIMED_OUT = 2,
	LIBUSB_TRANSFER_CANCELLED = 3,
	LIBUSB_TRANSFER_STALL = 4,
	LIBUSB_TRANSFER_NO_DEVICE = 5,
	LIBUSB_TRANSFER_OVERFLOW = 6,
};

// USB 2.0 9.6.6, bmAttributes bits 1..0.
enum libusb_transfer_type
{
	LIBUSB_TRANSFER_TYPE_CONTROL = 0,
	LIBUSB_TRANSFER_TYPE_ISOCHRONOUS = 1,
	LIBUSB_TRANSFER_TYPE_BULK = 2,
	LIBUSB_TRANSFER_TYPE_INTERRUPT = 3,
};
#define LIBUSB_TRANSFER_TYPE_MASK 0x03

// USB 2.0 9.3: the setup packet is eight bytes.
#define LIBUSB_CONTROL_SETUP_SIZE 8

// USB 2.0 Table 9-2, bmRequestType bit 7 (direction).
enum libusb_endpoint_direction
{
	LIBUSB_ENDPOINT_OUT = 0x00,
	LIBUSB_ENDPOINT_IN = 0x80,
};

// USB 2.0 Table 9-2, bmRequestType bits 6..5 (type).
enum libusb_request_type
{
	LIBUSB_REQUEST_TYPE_STANDARD = 0x00,
	LIBUSB_REQUEST_TYPE_CLASS = 0x20,
};

// USB 2.0 Table 9-2, bmRequestType bits 4..0 (recipient).
enum libusb_request_recipient
{
	LIBUSB_RECIPIENT_DEVICE = 0x00,
	LIBUSB_RECIPIENT_INTERFACE = 0x01,
	LIBUSB_RECIPIENT_ENDPOINT = 0x02,
};

// USB 2.0 Table 9-4, standard request codes.
enum libusb_standard_request
{
	LIBUSB_REQUEST_GET_STATUS = 0x00,
	LIBUSB_REQUEST_GET_DESCRIPTOR = 0x06,
	LIBUSB_REQUEST_GET_CONFIGURATION = 0x08,
	LIBUSB_REQUEST_SET_CONFIGURATION = 0x09,
	LIBUSB_REQUEST_SET_INTERFACE = 0x0B,
};

// USB 2.0 Table 9-8, the standard device descriptor. The passthrough constructor reads every field.
struct libusb_device_descriptor
{
	uint8_t bLength;
	uint8_t bDescriptorType;
	uint16_t bcdUSB;
	uint8_t bDeviceClass;
	uint8_t bDeviceSubClass;
	uint8_t bDeviceProtocol;
	uint8_t bMaxPacketSize0;
	uint16_t idVendor;
	uint16_t idProduct;
	uint16_t bcdDevice;
	uint8_t iManufacturer;
	uint8_t iProduct;
	uint8_t iSerialNumber;
	uint8_t bNumConfigurations;
};

struct libusb_iso_packet_descriptor
{
	unsigned int length;
	unsigned int actual_length;
	enum libusb_transfer_status status;
};

struct libusb_transfer;
typedef void (LIBUSB_CALL* libusb_transfer_cb_fn)(struct libusb_transfer* transfer);

// Complete, because sys_usbd.cpp formats and completes transfers unconditionally. Never allocated here.
struct libusb_transfer
{
	libusb_device_handle* dev_handle;
	uint8_t flags;
	unsigned char endpoint;
	unsigned char type;
	unsigned int timeout;
	enum libusb_transfer_status status;
	int length;
	int actual_length;
	libusb_transfer_cb_fn callback;
	void* user_data;
	unsigned char* buffer;
	int num_iso_packets;
	struct libusb_iso_packet_descriptor iso_packet_desc[];
};

// Succeeds with a non-null context: sys_usbd.cpp's thread loop, which completes fake transfers, runs only then.
static inline int libusb_init(libusb_context** ctx)
{
	static int sentinel;
	if (ctx)
		*ctx = (libusb_context*)&sentinel;
	return LIBUSB_SUCCESS;
}
static inline void libusb_exit(libusb_context* ctx) { (void)ctx; }
static inline ptrdiff_t libusb_get_device_list(libusb_context* ctx, libusb_device*** list)
{
	(void)ctx;
	if (list)
		*list = NULL;
	return 0;
}
static inline void libusb_free_device_list(libusb_device** list, int unref_devices) { (void)list; (void)unref_devices; }
static inline int libusb_handle_events_timeout_completed(libusb_context* ctx, struct timeval* tv, int* completed)
{
	(void)ctx; (void)tv; (void)completed;
	return LIBUSB_SUCCESS;
}
static inline const char* libusb_error_name(int code) { (void)code; return "LIBUSB_STANDIN"; }

// Emulated devices complete fake transfers and never touch these; submit never answers BUSY, which the passthrough retries forever.
static inline struct libusb_transfer* libusb_alloc_transfer(int iso_packets) { (void)iso_packets; return NULL; }
static inline void libusb_free_transfer(struct libusb_transfer* transfer) { (void)transfer; }
static inline int libusb_submit_transfer(struct libusb_transfer* transfer) { (void)transfer; return LIBUSB_ERROR_NOT_SUPPORTED; }
static inline void libusb_fill_control_setup(unsigned char* buffer, uint8_t bmRequestType, uint8_t bRequest, uint16_t wValue, uint16_t wIndex, uint16_t wLength)
{
	(void)buffer; (void)bmRequestType; (void)bRequest; (void)wValue; (void)wIndex; (void)wLength;
}
static inline void libusb_fill_control_transfer(struct libusb_transfer* transfer, libusb_device_handle* handle, unsigned char* buffer, libusb_transfer_cb_fn callback, void* user_data, unsigned int timeout)
{
	(void)transfer; (void)handle; (void)buffer; (void)callback; (void)user_data; (void)timeout;
}
static inline void libusb_fill_bulk_transfer(struct libusb_transfer* transfer, libusb_device_handle* handle, unsigned char endpoint, unsigned char* buffer, int length, libusb_transfer_cb_fn callback, void* user_data, unsigned int timeout)
{
	(void)transfer; (void)handle; (void)endpoint; (void)buffer; (void)length; (void)callback; (void)user_data; (void)timeout;
}
static inline void libusb_fill_interrupt_transfer(struct libusb_transfer* transfer, libusb_device_handle* handle, unsigned char endpoint, unsigned char* buffer, int length, libusb_transfer_cb_fn callback, void* user_data, unsigned int timeout)
{
	(void)transfer; (void)handle; (void)endpoint; (void)buffer; (void)length; (void)callback; (void)user_data; (void)timeout;
}
static inline void libusb_fill_iso_transfer(struct libusb_transfer* transfer, libusb_device_handle* handle, unsigned char endpoint, unsigned char* buffer, int length, int num_iso_packets, libusb_transfer_cb_fn callback, void* user_data, unsigned int timeout)
{
	(void)transfer; (void)handle; (void)endpoint; (void)buffer; (void)length; (void)num_iso_packets; (void)callback; (void)user_data; (void)timeout;
}

// Devices: with an empty list none of these is reached; each still answers honestly.
static inline libusb_device* libusb_ref_device(libusb_device* dev) { return dev; }
static inline void libusb_unref_device(libusb_device* dev) { (void)dev; }
static inline int libusb_get_device_descriptor(libusb_device* dev, struct libusb_device_descriptor* desc) { (void)dev; (void)desc; return LIBUSB_ERROR_NOT_SUPPORTED; }
static inline uint8_t libusb_get_port_number(libusb_device* dev) { (void)dev; return 0; }
static inline uint8_t libusb_get_device_address(libusb_device* dev) { (void)dev; return 0; }
static inline int libusb_open(libusb_device* dev, libusb_device_handle** handle) { (void)dev; (void)handle; return LIBUSB_ERROR_NOT_SUPPORTED; }
static inline void libusb_close(libusb_device_handle* handle) { (void)handle; }
static inline int libusb_set_auto_detach_kernel_driver(libusb_device_handle* handle, int enable) { (void)handle; (void)enable; return LIBUSB_ERROR_NOT_SUPPORTED; }
static inline int libusb_claim_interface(libusb_device_handle* handle, int interface_number) { (void)handle; (void)interface_number; return LIBUSB_ERROR_NOT_SUPPORTED; }
static inline int libusb_release_interface(libusb_device_handle* handle, int interface_number) { (void)handle; (void)interface_number; return LIBUSB_ERROR_NOT_SUPPORTED; }
static inline int libusb_get_configuration(libusb_device_handle* handle, int* config) { (void)handle; (void)config; return LIBUSB_ERROR_NOT_SUPPORTED; }
static inline int libusb_set_configuration(libusb_device_handle* handle, int configuration) { (void)handle; (void)configuration; return LIBUSB_ERROR_NOT_SUPPORTED; }
static inline int libusb_control_transfer(libusb_device_handle* handle, uint8_t request_type, uint8_t bRequest, uint16_t wValue, uint16_t wIndex, unsigned char* data, uint16_t wLength, unsigned int timeout)
{
	(void)handle; (void)request_type; (void)bRequest; (void)wValue; (void)wIndex; (void)data; (void)wLength; (void)timeout;
	return LIBUSB_ERROR_NOT_SUPPORTED;
}
