/**
 * Default storage, transfer, matching and persistence implementations by ydxc20091.
 *
 * <p>Copyright 2026 ydxc20091. Licensed under GPL-3.0-only.</p>
 * <p>Storage views retain their delegate's owner checks. Rate-limited ports should be kept
 * for the device lifetime rather than recreated per operation. Unknown or invalid persisted
 * records retain their original bytes and must be treated as protected data by bridges.</p>
 */
package com.ydxc20091.fluidcore.core;
