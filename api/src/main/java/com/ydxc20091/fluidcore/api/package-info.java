/**
 * Independent fluid contracts by ydxc20091.
 *
 * <p>Copyright 2026 ydxc20091. Licensed under Apache-2.0.</p>
 * <p>Amounts are measured in millibuckets; a bucket contains 1000 mB. Transactions are
 * synchronous and confined to the current owner thread and tick. Implementations verify
 * current ownership dynamically, including on Folia. Filters and component codecs must
 * perform deterministic local calculations; external effects belong after commit.</p>
 */
package com.ydxc20091.fluidcore.api;
