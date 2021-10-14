// This file was autogenerated by some hot garbage in the `uniffi` crate.
// Trust me, you don't want to mess with it!

@file:Suppress("NAME_SHADOWING")

package uniffi.bdk;

// Common helper code.
//
// Ideally this would live in a separate .kt file where it can be unittested etc
// in isolation, and perhaps even published as a re-useable package.
//
// However, it's important that the detils of how this helper code works (e.g. the
// way that different builtin types are passed across the FFI) exactly match what's
// expected by the Rust code on the other side of the interface. In practice right
// now that means coming from the exact some version of `uniffi` that was used to
// compile the Rust component. The easiest way to ensure this is to bundle the Kotlin
// helpers directly inline like we're doing here.

import com.sun.jna.Library
import com.sun.jna.Native
import com.sun.jna.Pointer
import com.sun.jna.Structure
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

// This is a helper for safely working with byte buffers returned from the Rust code.
// A rust-owned buffer is represented by its capacity, its current length, and a
// pointer to the underlying data.

@Structure.FieldOrder("capacity", "len", "data")
open class RustBuffer : Structure() {
    @JvmField var capacity: Int = 0
    @JvmField var len: Int = 0
    @JvmField var data: Pointer? = null

    class ByValue : RustBuffer(), Structure.ByValue
    class ByReference : RustBuffer(), Structure.ByReference

    companion object {
        internal fun alloc(size: Int = 0) = rustCall() { status ->
            _UniFFILib.INSTANCE.ffi_bdk_146a_rustbuffer_alloc(size, status)
        }

        internal fun free(buf: RustBuffer.ByValue) = rustCall() { status ->
            _UniFFILib.INSTANCE.ffi_bdk_146a_rustbuffer_free(buf, status)
        }

        internal fun reserve(buf: RustBuffer.ByValue, additional: Int) = rustCall() { status ->
            _UniFFILib.INSTANCE.ffi_bdk_146a_rustbuffer_reserve(buf, additional, status)
        }
    }

    @Suppress("TooGenericExceptionThrown")
    fun asByteBuffer() =
        this.data?.getByteBuffer(0, this.len.toLong())?.also {
            it.order(ByteOrder.BIG_ENDIAN)
        }
}

// This is a helper for safely passing byte references into the rust code.
// It's not actually used at the moment, because there aren't many things that you
// can take a direct pointer to in the JVM, and if we're going to copy something
// then we might as well copy it into a `RustBuffer`. But it's here for API
// completeness.

@Structure.FieldOrder("len", "data")
open class ForeignBytes : Structure() {
    @JvmField var len: Int = 0
    @JvmField var data: Pointer? = null

    class ByValue : ForeignBytes(), Structure.ByValue
}


// A helper for structured writing of data into a `RustBuffer`.
// This is very similar to `java.nio.ByteBuffer` but it knows how to grow
// the underlying `RustBuffer` on demand.
//
// TODO: we should benchmark writing things into a `RustBuffer` versus building
// up a bytearray and then copying it across.

class RustBufferBuilder() {
    var rbuf = RustBuffer.ByValue()
    var bbuf: ByteBuffer? = null

    init {
        val rbuf = RustBuffer.alloc(16) // Totally arbitrary initial size
        rbuf.writeField("len", 0)
        this.setRustBuffer(rbuf)
    }

    internal fun setRustBuffer(rbuf: RustBuffer.ByValue) {
        this.rbuf = rbuf
        this.bbuf = this.rbuf.data?.getByteBuffer(0, this.rbuf.capacity.toLong())?.also {
            it.order(ByteOrder.BIG_ENDIAN)
            it.position(rbuf.len)
        }
    }

    fun finalize() : RustBuffer.ByValue {
        val rbuf = this.rbuf
        // Ensure that the JVM-level field is written through to native memory
        // before turning the buffer, in case its recipient uses it in a context
        // JNA doesn't apply its automatic synchronization logic.
        rbuf.writeField("len", this.bbuf!!.position())
        this.setRustBuffer(RustBuffer.ByValue())
        return rbuf
    }

    fun discard() {
        val rbuf = this.finalize()
        RustBuffer.free(rbuf)
    }

    internal fun reserve(size: Int, write: (ByteBuffer) -> Unit) {
        // TODO: this will perform two checks to ensure we're not overflowing the buffer:
        // one here where we check if it needs to grow, and another when we call a write
        // method on the ByteBuffer. It might be cheaper to use exception-driven control-flow
        // here, trying the write and growing if it throws a `BufferOverflowException`.
        // Benchmarking needed.
        if (this.bbuf!!.position() + size > this.rbuf.capacity) {
            rbuf.writeField("len", this.bbuf!!.position())
            this.setRustBuffer(RustBuffer.reserve(this.rbuf, size))
        }
        write(this.bbuf!!)
    }

    fun putByte(v: Byte) {
        this.reserve(1) { bbuf ->
            bbuf.put(v)
        }
    }

    fun putShort(v: Short) {
        this.reserve(2) { bbuf ->
            bbuf.putShort(v)
        }
    }

    fun putInt(v: Int) {
        this.reserve(4) { bbuf ->
            bbuf.putInt(v)
        }
    }

    fun putLong(v: Long) {
        this.reserve(8) { bbuf ->
            bbuf.putLong(v)
        }
    }

    fun putFloat(v: Float) {
        this.reserve(4) { bbuf ->
            bbuf.putFloat(v)
        }
    }

    fun putDouble(v: Double) {
        this.reserve(8) { bbuf ->
            bbuf.putDouble(v)
        }
    }

    fun put(v: ByteArray) {
        this.reserve(v.size) { bbuf ->
            bbuf.put(v)
        }
    }
}

// Helpers for reading primitive data types from a bytebuffer.

internal fun<T> liftFromRustBuffer(rbuf: RustBuffer.ByValue, readItem: (ByteBuffer) -> T): T {
    val buf = rbuf.asByteBuffer()!!
    try {
       val item = readItem(buf)
       if (buf.hasRemaining()) {
           throw RuntimeException("junk remaining in buffer after lifting, something is very wrong!!")
       }
       return item
    } finally {
        RustBuffer.free(rbuf)
    }
}

internal fun<T> lowerIntoRustBuffer(v: T, writeItem: (T, RustBufferBuilder) -> Unit): RustBuffer.ByValue {
    // TODO: maybe we can calculate some sort of initial size hint?
    val buf = RustBufferBuilder()
    try {
        writeItem(v, buf)
        return buf.finalize()
    } catch (e: Throwable) {
        buf.discard()
        throw e
    }
}

// For every type used in the interface, we provide helper methods for conveniently
// lifting and lowering that type from C-compatible data, and for reading and writing
// values of that type in a buffer.




internal fun String.Companion.lift(rbuf: RustBuffer.ByValue): String {
    try {
        val byteArr = ByteArray(rbuf.len)
        rbuf.asByteBuffer()!!.get(byteArr)
        return byteArr.toString(Charsets.UTF_8)
    } finally {
        RustBuffer.free(rbuf)
    }
}

internal fun String.Companion.read(buf: ByteBuffer): String {
    val len = buf.getInt()
    val byteArr = ByteArray(len)
    buf.get(byteArr)
    return byteArr.toString(Charsets.UTF_8)
}

internal fun String.lower(): RustBuffer.ByValue {
    val byteArr = this.toByteArray(Charsets.UTF_8)
    // Ideally we'd pass these bytes to `ffi_bytebuffer_from_bytes`, but doing so would require us
    // to copy them into a JNA `Memory`. So we might as well directly copy them into a `RustBuffer`.
    val rbuf = RustBuffer.alloc(byteArr.size)
    rbuf.asByteBuffer()!!.put(byteArr)
    return rbuf
}

internal fun String.write(buf: RustBufferBuilder) {
    val byteArr = this.toByteArray(Charsets.UTF_8)
    buf.putInt(byteArr.size)
    buf.put(byteArr)
}



































@Synchronized
fun findLibraryName(componentName: String): String {
    val libOverride = System.getProperty("uniffi.component.${componentName}.libraryOverride")
    if (libOverride != null) {
        return libOverride
    }
    return "uniffi_bdk"
}

inline fun <reified Lib : Library> loadIndirect(
    componentName: String
): Lib {
    return Native.load<Lib>(findLibraryName(componentName), Lib::class.java)
}

// A JNA Library to expose the extern-C FFI definitions.
// This is an implementation detail which will be called internally by the public API.

internal interface _UniFFILib : Library {
    companion object {
        internal val INSTANCE: _UniFFILib by lazy { 
            loadIndirect<_UniFFILib>(componentName = "bdk")
            
            
        }
    }

    fun ffi_bdk_146a_OfflineWallet_object_free(ptr: Pointer,
    uniffi_out_err: RustCallStatus
    ): Unit

    fun bdk_146a_OfflineWallet_new(descriptor: RustBuffer.ByValue,network: RustBuffer.ByValue,database_config: RustBuffer.ByValue,
    uniffi_out_err: RustCallStatus
    ): Pointer

    fun bdk_146a_OfflineWallet_get_new_address(ptr: Pointer,
    uniffi_out_err: RustCallStatus
    ): RustBuffer.ByValue

    fun ffi_bdk_146a_rustbuffer_alloc(size: Int,
    uniffi_out_err: RustCallStatus
    ): RustBuffer.ByValue

    fun ffi_bdk_146a_rustbuffer_from_bytes(bytes: ForeignBytes.ByValue,
    uniffi_out_err: RustCallStatus
    ): RustBuffer.ByValue

    fun ffi_bdk_146a_rustbuffer_free(buf: RustBuffer.ByValue,
    uniffi_out_err: RustCallStatus
    ): Unit

    fun ffi_bdk_146a_rustbuffer_reserve(buf: RustBuffer.ByValue,additional: Int,
    uniffi_out_err: RustCallStatus
    ): RustBuffer.ByValue

    
}

// A handful of classes and functions to support the generated data structures.
// This would be a good candidate for isolating in its own ffi-support lib.



// Interface implemented by anything that can contain an object reference.
//
// Such types expose a `destroy()` method that must be called to cleanly
// dispose of the contained objects. Failure to call this method may result
// in memory leaks.
//
// The easiest way to ensure this method is called is to use the `.use`
// helper method to execute a block and destroy the object at the end.
interface Disposable {
    fun destroy()
}

inline fun <T : Disposable?, R> T.use(block: (T) -> R) =
    try {
        block(this)
    } finally {
        try {
            // N.B. our implementation is on the nullable type `Disposable?`.
            this?.destroy()
        } catch (e: Throwable) {
            // swallow
        }
    }

// The base class for all UniFFI Object types.
//
// This class provides core operations for working with the Rust `Arc<T>` pointer to
// the live Rust struct on the other side of the FFI.
//
// There's some subtlety here, because we have to be careful not to operate on a Rust
// struct after it has been dropped, and because we must expose a public API for freeing
// the Kotlin wrapper object in lieu of reliable finalizers. The core requirements are:
//
//   * Each `FFIObject` instance holds an opaque pointer to the underlying Rust struct.
//     Method calls need to read this pointer from the object's state and pass it in to
//     the Rust FFI.
//
//   * When an `FFIObject` is no longer needed, its pointer should be passed to a
//     special destructor function provided by the Rust FFI, which will drop the
//     underlying Rust struct.
//
//   * Given an `FFIObject` instance, calling code is expected to call the special
//     `destroy` method in order to free it after use, either by calling it explicitly
//     or by using a higher-level helper like the `use` method. Failing to do so will
//     leak the underlying Rust struct.
//
//   * We can't assume that calling code will do the right thing, and must be prepared
//     to handle Kotlin method calls executing concurrently with or even after a call to
//     `destroy`, and to handle multiple (possibly concurrent!) calls to `destroy`.
//
//   * We must never allow Rust code to operate on the underlying Rust struct after
//     the destructor has been called, and must never call the destructor more than once.
//     Doing so may trigger memory unsafety.
//
// If we try to implement this with mutual exclusion on access to the pointer, there is the
// possibility of a race between a method call and a concurrent call to `destroy`:
//
//    * Thread A starts a method call, reads the value of the pointer, but is interrupted
//      before it can pass the pointer over the FFI to Rust.
//    * Thread B calls `destroy` and frees the underlying Rust struct.
//    * Thread A resumes, passing the already-read pointer value to Rust and triggering
//      a use-after-free.
//
// One possible solution would be to use a `ReadWriteLock`, with each method call taking
// a read lock (and thus allowed to run concurrently) and the special `destroy` method
// taking a write lock (and thus blocking on live method calls). However, we aim not to
// generate methods with any hidden blocking semantics, and a `destroy` method that might
// block if called incorrectly seems to meet that bar.
//
// So, we achieve our goals by giving each `FFIObject` an associated `AtomicLong` counter to track
// the number of in-flight method calls, and an `AtomicBoolean` flag to indicate whether `destroy`
// has been called. These are updated according to the following rules:
//
//    * The initial value of the counter is 1, indicating a live object with no in-flight calls.
//      The initial value for the flag is false.
//
//    * At the start of each method call, we atomically check the counter.
//      If it is 0 then the underlying Rust struct has already been destroyed and the call is aborted.
//      If it is nonzero them we atomically increment it by 1 and proceed with the method call.
//
//    * At the end of each method call, we atomically decrement and check the counter.
//      If it has reached zero then we destroy the underlying Rust struct.
//
//    * When `destroy` is called, we atomically flip the flag from false to true.
//      If the flag was already true we silently fail.
//      Otherwise we atomically decrement and check the counter.
//      If it has reached zero then we destroy the underlying Rust struct.
//
// Astute readers may observe that this all sounds very similar to the way that Rust's `Arc<T>` works,
// and indeed it is, with the addition of a flag to guard against multiple calls to `destroy`.
//
// The overall effect is that the underlying Rust struct is destroyed only when `destroy` has been
// called *and* all in-flight method calls have completed, avoiding violating any of the expectations
// of the underlying Rust code.
//
// In the future we may be able to replace some of this with automatic finalization logic, such as using
// the new "Cleaner" functionaility in Java 9. The above scheme has been designed to work even if `destroy` is
// invoked by garbage-collection machinery rather than by calling code (which by the way, it's apparently also
// possible for the JVM to finalize an object while there is an in-flight call to one of its methods [1],
// so there would still be some complexity here).
//
// Sigh...all of this for want of a robust finalization mechanism.
//
// [1] https://stackoverflow.com/questions/24376768/can-java-finalize-an-object-when-it-is-still-in-scope/24380219
//
abstract class FFIObject(
    protected val pointer: Pointer
): Disposable, AutoCloseable {

    val wasDestroyed = AtomicBoolean(false)
    val callCounter = AtomicLong(1)

    open protected fun freeRustArcPtr() {
        // To be overridden in subclasses.
    }

    override fun destroy() {
        // Only allow a single call to this method.
        // TODO: maybe we should log a warning if called more than once?
        if (this.wasDestroyed.compareAndSet(false, true)) {
            // This decrement always matches the initial count of 1 given at creation time.
            if (this.callCounter.decrementAndGet() == 0L) {
                this.freeRustArcPtr()
            }
        }
    }

    @Synchronized
    override fun close() {
        this.destroy()
    }

    internal inline fun <R> callWithPointer(block: (ptr: Pointer) -> R): R {
        // Check and increment the call counter, to keep the object alive.
        // This needs a compare-and-set retry loop in case of concurrent updates.
        do {
            val c = this.callCounter.get()
            if (c == 0L) {
                throw IllegalStateException("${this.javaClass.simpleName} object has already been destroyed")
            }
            if (c == Long.MAX_VALUE) {
                throw IllegalStateException("${this.javaClass.simpleName} call counter would overflow")
            }
        } while (! this.callCounter.compareAndSet(c, c + 1L))
        // Now we can safely do the method call without the pointer being freed concurrently.
        try {
            return block(this.pointer)
        } finally {
            // This decrement aways matches the increment we performed above.
            if (this.callCounter.decrementAndGet() == 0L) {
                this.freeRustArcPtr()
            }
        }
    }
}





// Public interface members begin here.
// Public facing enums





enum class Network {
    BITCOIN,TESTNET,SIGNET,REGTEST;

    companion object {
        internal fun lift(rbuf: RustBuffer.ByValue): Network {
            return liftFromRustBuffer(rbuf) { buf -> Network.read(buf) }
        }

        internal fun read(buf: ByteBuffer) =
            try { values()[buf.getInt() - 1] }
            catch (e: IndexOutOfBoundsException) {
                throw RuntimeException("invalid enum value, something is very wrong!!", e)
            }
    }

    internal fun lower(): RustBuffer.ByValue {
        return lowerIntoRustBuffer(this, {v, buf -> v.write(buf)})
    }

    internal fun write(buf: RustBufferBuilder) {
        buf.putInt(this.ordinal + 1)
    }
}









sealed class DatabaseConfig  {
    
    data class Memory(
        val junk: String 
        ) : DatabaseConfig()
    
    data class Sled(
        val configuration: SledDbConfiguration 
        ) : DatabaseConfig()
    

    companion object {
        internal fun lift(rbuf: RustBuffer.ByValue): DatabaseConfig {
            return liftFromRustBuffer(rbuf) { buf -> DatabaseConfig.read(buf) }
        }

        internal fun read(buf: ByteBuffer): DatabaseConfig {
            return when(buf.getInt()) {
                1 -> DatabaseConfig.Memory(
                    String.read(buf)
                    )
                2 -> DatabaseConfig.Sled(
                    SledDbConfiguration.read(buf)
                    )
                else -> throw RuntimeException("invalid enum value, something is very wrong!!")
            }
        }
    }

    internal fun lower(): RustBuffer.ByValue {
        return lowerIntoRustBuffer(this, {v, buf -> v.write(buf)})
    }

    internal fun write(buf: RustBufferBuilder) {
        when(this) {
            is DatabaseConfig.Memory -> {
                buf.putInt(1)
                this.junk.write(buf)
                
            }
            is DatabaseConfig.Sled -> {
                buf.putInt(2)
                this.configuration.write(buf)
                
            }
        }.let { /* this makes the `when` an expression, which ensures it is exhaustive */ }
    }

    
    
}

// Error definitions
@Structure.FieldOrder("code", "error_buf")
internal open class RustCallStatus : Structure() {
    @JvmField var code: Int = 0
    @JvmField var error_buf: RustBuffer.ByValue = RustBuffer.ByValue()

    fun isSuccess(): Boolean {
        return code == 0
    }

    fun isError(): Boolean {
        return code == 1
    }

    fun isPanic(): Boolean {
        return code == 2
    }
}

class InternalException(message: String) : Exception(message)

// Each top-level error class has a companion object that can lift the error from the call status's rust buffer
interface CallStatusErrorHandler<E> {
    fun lift(error_buf: RustBuffer.ByValue): E;
}

// Error BdkError

sealed class BdkException(message: String): Exception(message)  {
        // Each variant is a nested class
        // Flat enums carries a string error message, so no special implementation is necessary.
        class InvalidU32Bytes(message: String) : BdkException(message)
        class Generic(message: String) : BdkException(message)
        class ScriptDoesntHaveAddressForm(message: String) : BdkException(message)
        class NoRecipients(message: String) : BdkException(message)
        class NoUtxosSelected(message: String) : BdkException(message)
        class OutputBelowDustLimit(message: String) : BdkException(message)
        class InsufficientFunds(message: String) : BdkException(message)
        class BnBTotalTriesExceeded(message: String) : BdkException(message)
        class BnBNoExactMatch(message: String) : BdkException(message)
        class UnknownUtxo(message: String) : BdkException(message)
        class TransactionNotFound(message: String) : BdkException(message)
        class TransactionConfirmed(message: String) : BdkException(message)
        class IrreplaceableTransaction(message: String) : BdkException(message)
        class FeeRateTooLow(message: String) : BdkException(message)
        class FeeTooLow(message: String) : BdkException(message)
        class FeeRateUnavailable(message: String) : BdkException(message)
        class MissingKeyOrigin(message: String) : BdkException(message)
        class Key(message: String) : BdkException(message)
        class ChecksumMismatch(message: String) : BdkException(message)
        class SpendingPolicyRequired(message: String) : BdkException(message)
        class InvalidPolicyPathException(message: String) : BdkException(message)
        class Signer(message: String) : BdkException(message)
        class InvalidNetwork(message: String) : BdkException(message)
        class InvalidProgressValue(message: String) : BdkException(message)
        class ProgressUpdateException(message: String) : BdkException(message)
        class InvalidOutpoint(message: String) : BdkException(message)
        class Descriptor(message: String) : BdkException(message)
        class AddressValidator(message: String) : BdkException(message)
        class Encode(message: String) : BdkException(message)
        class Miniscript(message: String) : BdkException(message)
        class Bip32(message: String) : BdkException(message)
        class Secp256k1(message: String) : BdkException(message)
        class Json(message: String) : BdkException(message)
        class Hex(message: String) : BdkException(message)
        class Psbt(message: String) : BdkException(message)
        class PsbtParse(message: String) : BdkException(message)
        class Electrum(message: String) : BdkException(message)
        class Sled(message: String) : BdkException(message)
        

    companion object ErrorHandler : CallStatusErrorHandler<BdkException> {
        override fun lift(error_buf: RustBuffer.ByValue): BdkException {
            return liftFromRustBuffer(error_buf) { error_buf -> read(error_buf) }
        }

        fun read(error_buf: ByteBuffer): BdkException {
            
                return when(error_buf.getInt()) {
                1 -> BdkException.InvalidU32Bytes(String.read(error_buf))
                2 -> BdkException.Generic(String.read(error_buf))
                3 -> BdkException.ScriptDoesntHaveAddressForm(String.read(error_buf))
                4 -> BdkException.NoRecipients(String.read(error_buf))
                5 -> BdkException.NoUtxosSelected(String.read(error_buf))
                6 -> BdkException.OutputBelowDustLimit(String.read(error_buf))
                7 -> BdkException.InsufficientFunds(String.read(error_buf))
                8 -> BdkException.BnBTotalTriesExceeded(String.read(error_buf))
                9 -> BdkException.BnBNoExactMatch(String.read(error_buf))
                10 -> BdkException.UnknownUtxo(String.read(error_buf))
                11 -> BdkException.TransactionNotFound(String.read(error_buf))
                12 -> BdkException.TransactionConfirmed(String.read(error_buf))
                13 -> BdkException.IrreplaceableTransaction(String.read(error_buf))
                14 -> BdkException.FeeRateTooLow(String.read(error_buf))
                15 -> BdkException.FeeTooLow(String.read(error_buf))
                16 -> BdkException.FeeRateUnavailable(String.read(error_buf))
                17 -> BdkException.MissingKeyOrigin(String.read(error_buf))
                18 -> BdkException.Key(String.read(error_buf))
                19 -> BdkException.ChecksumMismatch(String.read(error_buf))
                20 -> BdkException.SpendingPolicyRequired(String.read(error_buf))
                21 -> BdkException.InvalidPolicyPathException(String.read(error_buf))
                22 -> BdkException.Signer(String.read(error_buf))
                23 -> BdkException.InvalidNetwork(String.read(error_buf))
                24 -> BdkException.InvalidProgressValue(String.read(error_buf))
                25 -> BdkException.ProgressUpdateException(String.read(error_buf))
                26 -> BdkException.InvalidOutpoint(String.read(error_buf))
                27 -> BdkException.Descriptor(String.read(error_buf))
                28 -> BdkException.AddressValidator(String.read(error_buf))
                29 -> BdkException.Encode(String.read(error_buf))
                30 -> BdkException.Miniscript(String.read(error_buf))
                31 -> BdkException.Bip32(String.read(error_buf))
                32 -> BdkException.Secp256k1(String.read(error_buf))
                33 -> BdkException.Json(String.read(error_buf))
                34 -> BdkException.Hex(String.read(error_buf))
                35 -> BdkException.Psbt(String.read(error_buf))
                36 -> BdkException.PsbtParse(String.read(error_buf))
                37 -> BdkException.Electrum(String.read(error_buf))
                38 -> BdkException.Sled(String.read(error_buf))
                else -> throw RuntimeException("invalid error enum value, something is very wrong!!")
            }
        }
    }

    
    
}


// Helpers for calling Rust
// In practice we usually need to be synchronized to call this safely, so it doesn't
// synchronize itself

// Call a rust function that returns a Result<>.  Pass in the Error class companion that corresponds to the Err
private inline fun <U, E: Exception> rustCallWithError(errorHandler: CallStatusErrorHandler<E>, callback: (RustCallStatus) -> U): U {
    var status = RustCallStatus();
    val return_value = callback(status)
    if (status.isSuccess()) {
        return return_value
    } else if (status.isError()) {
        throw errorHandler.lift(status.error_buf)
    } else if (status.isPanic()) {
        // when the rust code sees a panic, it tries to construct a rustbuffer
        // with the message.  but if that code panics, then it just sends back
        // an empty buffer.
        if (status.error_buf.len > 0) {
            throw InternalException(String.lift(status.error_buf))
        } else {
            throw InternalException("Rust panic")
        }
    } else {
        throw InternalException("Unknown rust call status: $status.code")
    }
}

// CallStatusErrorHandler implementation for times when we don't expect a CALL_ERROR
object NullCallStatusErrorHandler: CallStatusErrorHandler<InternalException> {
    override fun lift(error_buf: RustBuffer.ByValue): InternalException {
        RustBuffer.free(error_buf)
        return InternalException("Unexpected CALL_ERROR")
    }
}

// Call a rust function that returns a plain value
private inline fun <U> rustCall(callback: (RustCallStatus) -> U): U {
    return rustCallWithError(NullCallStatusErrorHandler, callback);
}

// Public facing records

data class SledDbConfiguration (
    var path: String, 
    var treeName: String 
)  {
    companion object {
        internal fun lift(rbuf: RustBuffer.ByValue): SledDbConfiguration {
            return liftFromRustBuffer(rbuf) { buf -> SledDbConfiguration.read(buf) }
        }

        internal fun read(buf: ByteBuffer): SledDbConfiguration {
            return SledDbConfiguration(
            String.read(buf),
            String.read(buf)
            )
        }
    }

    internal fun lower(): RustBuffer.ByValue {
        return lowerIntoRustBuffer(this, {v, buf -> v.write(buf)})
    }

    internal fun write(buf: RustBufferBuilder) {
            this.path.write(buf)
        
            this.treeName.write(buf)
        
    }

    
    
}


// Namespace functions


// Objects


public interface OfflineWalletInterface {
    fun getNewAddress(): String
    
}


class OfflineWallet(
    pointer: Pointer
) : FFIObject(pointer), OfflineWalletInterface {
    constructor(descriptor: String, network: Network, databaseConfig: DatabaseConfig ) :
        this(
    rustCallWithError(BdkException) { status ->
    _UniFFILib.INSTANCE.bdk_146a_OfflineWallet_new(descriptor.lower(), network.lower(), databaseConfig.lower() ,status)
})

    /**
     * Disconnect the object from the underlying Rust object.
     * 
     * It can be called more than once, but once called, interacting with the object
     * causes an `IllegalStateException`.
     * 
     * Clients **must** call this method once done with the object, or cause a memory leak.
     */
    override protected fun freeRustArcPtr() {
        rustCall() { status ->
            _UniFFILib.INSTANCE.ffi_bdk_146a_OfflineWallet_object_free(this.pointer, status)
        }
    }

    internal fun lower(): Pointer = callWithPointer { it }

    internal fun write(buf: RustBufferBuilder) {
        // The Rust code always expects pointers written as 8 bytes,
        // and will fail to compile if they don't fit.
        buf.putLong(Pointer.nativeValue(this.lower()))
    }

    override fun getNewAddress(): String =
        callWithPointer {
    rustCall() { status ->
    _UniFFILib.INSTANCE.bdk_146a_OfflineWallet_get_new_address(it,  status)
}
        }.let {
            String.lift(it)
        }
    
    

    companion object {
        internal fun lift(ptr: Pointer): OfflineWallet {
            return OfflineWallet(ptr)
        }

        internal fun read(buf: ByteBuffer): OfflineWallet {
            // The Rust code always writes pointers as 8 bytes, and will
            // fail to compile if they don't fit.
            return OfflineWallet.lift(Pointer(buf.getLong()))
        }

        
    }
}


// Callback Interfaces


