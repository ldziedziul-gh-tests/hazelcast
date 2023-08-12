/*
 * Copyright (c) 2008-2023, Hazelcast, Inc. All Rights Reserved.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.hazelcast.internal.tpcengine.iouring;

import com.hazelcast.internal.tpcengine.file.AsyncFile;
import com.hazelcast.internal.tpcengine.file.StorageRequest;
import com.hazelcast.internal.tpcengine.file.StorageScheduler;
import com.hazelcast.internal.tpcengine.iobuffer.IOBuffer;
import com.hazelcast.internal.tpcengine.iobuffer.IOBufferAllocator;
import com.hazelcast.internal.tpcengine.iobuffer.NonConcurrentIOBufferAllocator;
import com.hazelcast.internal.tpcengine.util.CircularQueue;
import com.hazelcast.internal.tpcengine.util.SlabAllocator;
import com.hazelcast.internal.tpcengine.util.UnsafeLocator;
import sun.misc.Unsafe;

import java.nio.charset.StandardCharsets;

import static com.hazelcast.internal.tpcengine.iouring.Uring.IORING_FSYNC_DATASYNC;
import static com.hazelcast.internal.tpcengine.iouring.Uring.IORING_OP_CLOSE;
import static com.hazelcast.internal.tpcengine.iouring.Uring.IORING_OP_FALLOCATE;
import static com.hazelcast.internal.tpcengine.iouring.Uring.IORING_OP_FSYNC;
import static com.hazelcast.internal.tpcengine.iouring.Uring.IORING_OP_NOP;
import static com.hazelcast.internal.tpcengine.iouring.Uring.IORING_OP_OPENAT;
import static com.hazelcast.internal.tpcengine.iouring.Uring.IORING_OP_READ;
import static com.hazelcast.internal.tpcengine.iouring.Uring.IORING_OP_WRITE;
import static com.hazelcast.internal.tpcengine.iouring.Uring.opcodeToString;
import static com.hazelcast.internal.tpcengine.iouring.SubmissionQueue.OFFSET_SQE_addr;
import static com.hazelcast.internal.tpcengine.iouring.SubmissionQueue.OFFSET_SQE_fd;
import static com.hazelcast.internal.tpcengine.iouring.SubmissionQueue.OFFSET_SQE_flags;
import static com.hazelcast.internal.tpcengine.iouring.SubmissionQueue.OFFSET_SQE_ioprio;
import static com.hazelcast.internal.tpcengine.iouring.SubmissionQueue.OFFSET_SQE_len;
import static com.hazelcast.internal.tpcengine.iouring.SubmissionQueue.OFFSET_SQE_off;
import static com.hazelcast.internal.tpcengine.iouring.SubmissionQueue.OFFSET_SQE_opcode;
import static com.hazelcast.internal.tpcengine.iouring.SubmissionQueue.OFFSET_SQE_rw_flags;
import static com.hazelcast.internal.tpcengine.iouring.SubmissionQueue.OFFSET_SQE_user_data;
import static com.hazelcast.internal.tpcengine.iouring.SubmissionQueue.SIZEOF_SQE;
import static com.hazelcast.internal.tpcengine.util.BitUtil.SIZEOF_CHAR;
import static java.lang.Math.min;


/**
 * A io_uring specific {@link StorageScheduler} that processes all requests
 * in FIFO order.
 * <p/>
 * There are 3 important queues:
 * <ol>
 *     <li>stagingQueue: this is the queue where scheduled request initially
 *     end up.</li>
 *     <li>submissionQueue: on every scheduled tick, requests from the staging
 *     queue are moved to the submission queue.</li>
 *     <li>
 *         completionQueue: once io_uring has completed the requests, the
 *         completed requests end up at the completion queue.
 *     </li>
 * </ol>
 */
// todo: remove magic number
@SuppressWarnings({"checkstyle:MethodLength",
        "checkstyle:MemberName",
        "checkstyle:LocalVariableName",
        "checkstyle:MagicNumber"})
public class UringFifoStorageScheduler implements StorageScheduler {

    private static final Unsafe UNSAFE = UnsafeLocator.UNSAFE;

    // To prevent intermediate string litter for every IOException the msgBuilder is recycled.
    private final StringBuilder msgBuilder = new StringBuilder();
    private final SlabAllocator<UringStorageRequest> requestAllocator;
    private final CircularQueue<UringStorageRequest> stagingQueue;
    private final SubmissionQueue submissionQueue;
    private final CompletionQueue completionQueue;
    private final int maxIoDepth;
    private final IOBufferAllocator pathAllocator;
    private int ioDepth;

    public UringFifoStorageScheduler(Uring uring,
                                     int maxIoDepth,
                                     int capacity) {
        this.maxIoDepth = maxIoDepth;
        this.requestAllocator = new SlabAllocator<>(capacity, UringStorageRequest::new);
        this.pathAllocator = new NonConcurrentIOBufferAllocator(512, true);
        this.stagingQueue = new CircularQueue<>(capacity);
        this.submissionQueue = uring.sq();
        this.completionQueue = uring.cq();
    }

    @Override
    public StorageRequest allocate() {
        return requestAllocator.allocate();
    }

    @Override
    public void schedule(StorageRequest req) {
        if (!stagingQueue.offer((UringStorageRequest) req)) {
            throw new IllegalStateException("Too many concurrent requests");
        }
    }

    @Override
    public void tick() {
        // Submits as many staged requests as allowed to the submission queue.
        // Completion events are processed in the eventloop, so we
        // don't need to deal with that here.
        int submitCount = min(maxIoDepth - ioDepth, stagingQueue.size());

        for (int k = 0; k < submitCount; k++) {
            int sqIndex = submissionQueue.nextIndex();
            if (sqIndex < 0) {
                // the submission queue is full
                break;
            }

            ioDepth++;
            UringStorageRequest req = stagingQueue.poll();
            long userdata = completionQueue.nextTmpHandlerId();
            completionQueue.register(userdata, req);
            req.writeSqe(sqIndex, userdata);
        }
    }

    final class UringStorageRequest extends StorageRequest implements CompletionHandler {

        void writeSqe(int sqIndex, long userdata) {
            long sqeAddr = submissionQueue.sqesAddr + sqIndex * SIZEOF_SQE;
            byte sqe_opcode;
            int sqe_fd = 0;
            long sqe_addr = 0;
            int sqe_rw_flags = 0;
            int sqe_len = 0;
            switch (opcode) {
                case STR_REQ_OP_NOP:
                    sqe_opcode = IORING_OP_NOP;
                    break;
                case STR_REQ_OP_READ:
                    sqe_opcode = IORING_OP_READ;
                    sqe_fd = file.fd;
                    sqe_addr = buffer.address();
                    sqe_len = length;
                    break;
                case STR_REQ_OP_WRITE:
                    sqe_opcode = IORING_OP_WRITE;
                    sqe_fd = file.fd;
                    sqe_addr = buffer.address();
                    sqe_len = length;
                    break;
                case STR_REQ_OP_FSYNC:
                    sqe_opcode = IORING_OP_FSYNC;
                    sqe_fd = file.fd;
                    break;
                case STR_REQ_OP_FDATASYNC:
                    sqe_opcode = IORING_OP_FSYNC;
                    sqe_fd = file.fd;
                    // todo: //            request.opcode = IORING_OP_FSYNC;
                    ////            // The IOURING_FSYNC_DATASYNC maps to the same position as the rw-flags
                    ////            request.rwFlags = IORING_FSYNC_DATASYNC;
                    sqe_rw_flags = IORING_FSYNC_DATASYNC;
                    break;
                case STR_REQ_OP_OPEN:
                    sqe_opcode = IORING_OP_OPENAT;
                    buffer = pathAsIOBuffer();
                    sqe_addr = buffer.address();
                    sqe_len = permissions;
                    sqe_rw_flags = flags;
                    break;
                case STR_REQ_OP_CLOSE:
                    sqe_opcode = IORING_OP_CLOSE;
                    sqe_fd = file.fd;
                    break;
                case STR_REQ_OP_FALLOCATE:
                    sqe_opcode = IORING_OP_FALLOCATE;
                    break;
                default:
                    throw new RuntimeException("Unknown opcode: " + opcode + " this=" + this);
            }

            UNSAFE.putByte(sqeAddr + OFFSET_SQE_opcode, sqe_opcode);
            UNSAFE.putInt(sqeAddr + OFFSET_SQE_fd, sqe_fd);
            UNSAFE.putByte(sqeAddr + OFFSET_SQE_flags, (byte) 0);
            UNSAFE.putShort(sqeAddr + OFFSET_SQE_ioprio, (short) 0);
            UNSAFE.putInt(sqeAddr + OFFSET_SQE_fd, sqe_fd);
            UNSAFE.putLong(sqeAddr + OFFSET_SQE_off, offset);
            UNSAFE.putLong(sqeAddr + OFFSET_SQE_addr, sqe_addr);
            UNSAFE.putInt(sqeAddr + OFFSET_SQE_len, sqe_len);
            UNSAFE.putInt(sqeAddr + OFFSET_SQE_rw_flags, sqe_rw_flags);
            UNSAFE.putLong(sqeAddr + OFFSET_SQE_user_data, userdata);
        }

        IOBuffer pathAsIOBuffer() {
            // todo: unwanted litter.
            byte[] chars = file.path().getBytes(StandardCharsets.UTF_8);

            IOBuffer pathBuffer = pathAllocator.allocate(chars.length + SIZEOF_CHAR);
            pathBuffer.writeBytes(chars);
            // C strings end with \0
            pathBuffer.writeChar('\0');
            pathBuffer.flip();
            return pathBuffer;
        }

        @Override
        public void completeRequest(int res, int flags, long userdata) {
            ioDepth--;

            if (res >= 0) {
                AsyncFile.Metrics metrics = file.metrics();
                switch (opcode) {
                    case STR_REQ_OP_NOP:
                        metrics.incNops();
                        break;
                    case STR_REQ_OP_READ:
                        buffer.incPosition(res);
                        metrics.incReads();
                        metrics.incBytesRead(res);
                        break;
                    case STR_REQ_OP_WRITE:
                        buffer.incPosition(res);
                        metrics.incWrites();
                        metrics.incBytesWritten(res);
                        break;
                    case STR_REQ_OP_FSYNC:
                        metrics.incFsyncs();
                        break;
                    case STR_REQ_OP_FDATASYNC:
                        metrics.incFdatasyncs();
                        break;
                    case STR_REQ_OP_OPEN:
                        // this buffer is not passed from the outside, it is passed in the writeSqe function above.
                        buffer.release();
                        file.fd = res;
                        break;
                    case STR_REQ_OP_CLOSE:
                        file.fd = -1;
                        break;
                    case STR_REQ_OP_FALLOCATE:
                        break;
                    default:
                        throw new IllegalStateException("Unknown opcode: " + opcode);
                }

                promise.complete(res);
            } else {
                handleError(res);
            }

            rwFlags = 0;
            flags = 0;
            opcode = 0;
            length = 0;
            offset = 0;
            permissions = 0;
            buffer = null;
            file = null;
            promise = null;
            dev = null;
            requestAllocator.free(this);
        }

        private void handleError(int res) {
            msgBuilder.setLength(0);
            String manUrl = null;
            switch (opcode) {
                case STR_REQ_OP_NOP:
                    msgBuilder.append("Failed to perform a nop on file ").append(file.path()).append(". ");
                    break;
                case STR_REQ_OP_READ:
                    msgBuilder.append("Failed to a read from file ").append(file.path()).append(". ");
                    manUrl = "https://man7.org/linux/man-pages/man2/pwrite.2.html";
                    break;
                case STR_REQ_OP_WRITE:
                    msgBuilder.append("Failed to a write to file ").append(file.path()).append(". ");
                    manUrl = "https://man7.org/linux/man-pages/man3/pwrite.3p.html";
                    break;
                case STR_REQ_OP_FSYNC:
                    msgBuilder.append("Failed to perform a fsync on file ").append(file.path()).append(". ");
                    manUrl = "https://man7.org/linux/man-pages/man2/fsync.2.html";
                    break;
                case STR_REQ_OP_FDATASYNC:
                    msgBuilder.append("Failed to perform a fdatasync on file ").append(file.path()).append(". ");
                    manUrl = "https://man7.org/linux/man-pages/man2/fsync.2.html";
                    break;
                case STR_REQ_OP_OPEN:
                    buffer.release();
                    msgBuilder.append("Failed to open file ").append(file.path()).append(". ");
                    manUrl = "https://man7.org/linux/man-pages/man2/open.2.html";
                    break;
                case STR_REQ_OP_CLOSE:
                    msgBuilder.append("Failed to close file ").append(file.path()).append(". ");
                    manUrl = "https://man7.org/linux/man-pages/man2/close.2.html";
                    break;
                case STR_REQ_OP_FALLOCATE:
                    msgBuilder.append("Failed to fallocate on file ").append(file.path()).append(". ");
                    manUrl = "https://man7.org/linux/man-pages/man2/fallocate.2.html";
                    break;
                default:
                    throw new IllegalStateException("Unknown opcode: " + opcode);
            }

            msgBuilder.append("Error-message '").append(Linux.strerror(-res)).append("' ")
                    .append("Error-code ").append(Linux.errorcode(-res)).append(". ")
                    .append(this);
            if (manUrl != null) {
                msgBuilder.append(" See ").append(manUrl).append(" for more details. ");
            }

            promise.completeWithIOException(msgBuilder.toString(), null);
        }

        @Override
        public String toString() {
            return "StorageRequest{"
                    + "file=" + file
                    + ", offset=" + offset
                    + ", length=" + length
                    + ", opcode=" + opcodeToString(opcode)
                    + ", flags=" + flags
                    + ", permissions=" + permissions
                    + ", rwFlags=" + rwFlags
                    + ", buf=" + (buffer == null ? "null" : buffer.toDebugString())
                    + "}";
        }
    }
}
