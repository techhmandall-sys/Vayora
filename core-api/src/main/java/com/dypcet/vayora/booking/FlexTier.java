package com.dypcet.vayora.booking;

/**
 * What a passenger agrees to, when booking, should the driver withdraw the ride. Chosen by
 * the passenger alone: nothing about them is predicted, and the price is the same.
 */
public enum FlexTier {
    /** This ride only. Other rides are suggested, nothing is booked. */
    EXACT,
    /** Book me on the closest other ride within the configured window, automatically. */
    FLEXIBLE,
    /** Book me on the closest other ride that day, automatically. */
    ANY_TIME
}
