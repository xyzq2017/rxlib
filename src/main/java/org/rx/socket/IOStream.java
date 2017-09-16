package org.rx.socket;

import org.rx.SystemException;
import org.rx.cache.BytesSegment;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.function.Function;
import java.util.function.Predicate;

import static org.rx.Contract.isNull;
import static org.rx.Contract.require;

public final class IOStream implements AutoCloseable {
    private static final Predicate<IOStream> DefaultIsOpen = p -> true;
    private final InputStream                inputStream;
    private final OutputStream               outputStream;
    private final BytesSegment segment;

    public InputStream getInputStream() {
        return inputStream;
    }

    public OutputStream getOutputStream() {
        return outputStream;
    }

    public BytesSegment getSegment() {
        return segment;
    }

    public IOStream(InputStream inputStream, OutputStream outputStream, BytesSegment segment) {
        require(inputStream, outputStream, segment);

        this.inputStream = inputStream;
        this.outputStream = outputStream;
        this.segment = segment;
    }

    @Override
    public void close() {
        try {
            inputStream.close();
            outputStream.close();
            segment.close();
        } catch (IOException ex) {
            throw new SystemException(ex);
        }
    }

    public int directData(Predicate<IOStream> isOpen, Function<Integer, Boolean> eachFunc) {
        isOpen = isNull(isOpen, DefaultIsOpen);
        try {
            int recv = -1;
            while (isOpen.test(this) && (recv = inputStream.read(segment.array, segment.offset, segment.count)) >= 0) {
                if (recv == 0) {
                    break;
                }
                if (!isOpen.test(this)) {
                    break;
                }
                outputStream.write(segment.array, segment.offset, recv);
                if (eachFunc != null) {
                    if (!eachFunc.apply(recv)) {
                        break;
                    }
                }
            }
            outputStream.flush();
            return recv;
        } catch (IOException ex) {
            throw new SystemException(ex);
        }
    }
}
