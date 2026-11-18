package org.example.exchange.wire;

import com.google.protobuf.CodedInputStream;
import com.google.protobuf.CodedOutputStream;

import java.io.ByteArrayOutputStream;
import java.io.EOFException;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Objects;

public final class ExchangeWire {
    private ExchangeWire() {
    }

    public static final class Packet {
        private final Object[] values;

        private Packet(Object[] source) {
            values = source.clone();
            if (values[17] != null) {
                values[17] = List.copyOf(items(values[17]));
            }
        }

        public static Builder newBuilder() {
            return new Builder();
        }

        public Builder toBuilder() {
            return new Builder(values);
        }

        private static String stringValue(Object value) {
            return value == null ? "" : (String) value;
        }

        private static long longValue(Object value) {
            return value == null ? 0L : (Long) value;
        }

        @SuppressWarnings("unchecked")
        private static List<Packet> items(Object value) {
            return (List<Packet>) value;
        }

        public String getOp() {
            return stringValue(values[1]);
        }

        public String getClientId() {
            return stringValue(values[2]);
        }

        public String getBase() {
            return stringValue(values[3]);
        }

        public String getQuote() {
            return stringValue(values[4]);
        }

        public String getSide() {
            return stringValue(values[5]);
        }

        public String getQuantity() {
            return stringValue(values[6]);
        }

        public String getPrice() {
            return stringValue(values[7]);
        }

        public String getRemaining() {
            return stringValue(values[9]);
        }

        public String getBuyerId() {
            return stringValue(values[13]);
        }

        public String getSellerId() {
            return stringValue(values[14]);
        }

        public String getError() {
            return stringValue(values[15]);
        }

        public long getOrderId() {
            return longValue(values[8]);
        }

        public long getTradeId() {
            return longValue(values[10]);
        }

        public long getBuyOrderId() {
            return longValue(values[11]);
        }

        public long getSellOrderId() {
            return longValue(values[12]);
        }

        public long getNotificationId() {
            return longValue(values[18]);
        }

        public long getCancelledCount() {
            return longValue(values[19]);
        }

        public boolean getFound() {
            return Boolean.TRUE.equals(values[16]);
        }

        public List<Packet> getItemsList() {
            return values[17] == null ? List.of() : items(values[17]);
        }

        public int getItemsCount() {
            return getItemsList().size();
        }

        public Packet getItems(int index) {
            return getItemsList().get(index);
        }

        private byte[] toBytes() throws IOException {
            ByteArrayOutputStream bytes = new ByteArrayOutputStream();
            CodedOutputStream output = CodedOutputStream.newInstance(bytes);
            if (!getOp().isEmpty()) {
                output.writeString(1, getOp());
            }
            if (!getClientId().isEmpty()) {
                output.writeString(2, getClientId());
            }
            if (!getBase().isEmpty()) {
                output.writeString(3, getBase());
            }
            if (!getQuote().isEmpty()) {
                output.writeString(4, getQuote());
            }
            if (!getSide().isEmpty()) {
                output.writeString(5, getSide());
            }
            if (!getQuantity().isEmpty()) {
                output.writeString(6, getQuantity());
            }
            if (!getPrice().isEmpty()) {
                output.writeString(7, getPrice());
            }
            if (getOrderId() != 0L) {
                output.writeInt64(8, getOrderId());
            }
            if (!getRemaining().isEmpty()) {
                output.writeString(9, getRemaining());
            }
            if (getTradeId() != 0L) {
                output.writeInt64(10, getTradeId());
            }
            if (getBuyOrderId() != 0L) {
                output.writeInt64(11, getBuyOrderId());
            }
            if (getSellOrderId() != 0L) {
                output.writeInt64(12, getSellOrderId());
            }
            if (!getBuyerId().isEmpty()) {
                output.writeString(13, getBuyerId());
            }
            if (!getSellerId().isEmpty()) {
                output.writeString(14, getSellerId());
            }
            if (!getError().isEmpty()) {
                output.writeString(15, getError());
            }
            if (getNotificationId() != 0L) {
                output.writeInt64(18, getNotificationId());
            }
            if (getFound()) {
                output.writeBool(16, true);
            }
            if (getCancelledCount() != 0L) {
                output.writeInt64(19, getCancelledCount());
            }
            for (Packet item : getItemsList()) {
                output.writeByteArray(17, item.toBytes());
            }
            output.flush();
            return bytes.toByteArray();
        }

        public void writeDelimitedTo(OutputStream stream) throws IOException {
            byte[] payload = toBytes();
            CodedOutputStream output = CodedOutputStream.newInstance(stream);
            output.writeUInt32NoTag(payload.length);
            output.writeRawBytes(payload);
            output.flush();
        }

        public static Packet parseDelimitedFrom(InputStream stream) throws IOException {
            int current = stream.read();
            if (current == -1) {
                return null;
            }
            int length = current & 127;
            int shift = 7;
            while ((current & 128) != 0) {
                if (shift >= 35) {
                    throw new IOException("Invalid protobuf frame length");
                }
                current = stream.read();
                if (current == -1) {
                    throw new EOFException("Incomplete protobuf frame length");
                }
                length |= (current & 127) << shift;
                shift += 7;
            }
            if (length < 0 || length > 67108864) {
                throw new IOException("Invalid protobuf frame size");
            }
            byte[] payload = stream.readNBytes(length);
            if (payload.length != length) {
                throw new EOFException("Incomplete protobuf frame");
            }
            return parse(payload);
        }

        private static Packet parse(byte[] bytes) throws IOException {
            CodedInputStream input = CodedInputStream.newInstance(bytes);
            Builder builder = newBuilder();
            while (!input.isAtEnd()) {
                int tag = input.readTag();
                if (tag == 0) {
                    break;
                }
                switch (tag >>> 3) {
                    case 1 -> builder.setOp(input.readStringRequireUtf8());
                    case 2 -> builder.setClientId(input.readStringRequireUtf8());
                    case 3 -> builder.setBase(input.readStringRequireUtf8());
                    case 4 -> builder.setQuote(input.readStringRequireUtf8());
                    case 5 -> builder.setSide(input.readStringRequireUtf8());
                    case 6 -> builder.setQuantity(input.readStringRequireUtf8());
                    case 7 -> builder.setPrice(input.readStringRequireUtf8());
                    case 9 -> builder.setRemaining(input.readStringRequireUtf8());
                    case 13 -> builder.setBuyerId(input.readStringRequireUtf8());
                    case 14 -> builder.setSellerId(input.readStringRequireUtf8());
                    case 15 -> builder.setError(input.readStringRequireUtf8());
                    case 8 -> builder.setOrderId(input.readInt64());
                    case 10 -> builder.setTradeId(input.readInt64());
                    case 11 -> builder.setBuyOrderId(input.readInt64());
                    case 12 -> builder.setSellOrderId(input.readInt64());
                    case 18 -> builder.setNotificationId(input.readInt64());
                    case 19 -> builder.setCancelledCount(input.readInt64());
                    case 16 -> builder.setFound(input.readBool());
                    case 17 -> builder.addItems(parse(input.readByteArray()));
                    default -> {
                        if (!input.skipField(tag)) {
                            throw new IOException("Invalid protobuf field");
                        }
                    }
                }
            }
            return builder.build();
        }

        @Override
        public boolean equals(Object other) {
            return other instanceof Packet packet && Arrays.deepEquals(values, packet.values);
        }

        @Override
        public int hashCode() {
            return Arrays.deepHashCode(values);
        }

        public static final class Builder {
            private final Object[] values;

            private Builder() {
                values = new Object[20];
            }

            private Builder(Object[] source) {
                values = source.clone();
                if (values[17] != null) {
                    values[17] = new ArrayList<>(items(values[17]));
                }
            }

            public Builder setOp(String value) {
                values[1] = Objects.requireNonNull(value);
                return this;
            }

            public Builder setClientId(String value) {
                values[2] = Objects.requireNonNull(value);
                return this;
            }

            public Builder setBase(String value) {
                values[3] = Objects.requireNonNull(value);
                return this;
            }

            public Builder setQuote(String value) {
                values[4] = Objects.requireNonNull(value);
                return this;
            }

            public Builder setSide(String value) {
                values[5] = Objects.requireNonNull(value);
                return this;
            }

            public Builder setQuantity(String value) {
                values[6] = Objects.requireNonNull(value);
                return this;
            }

            public Builder setPrice(String value) {
                values[7] = Objects.requireNonNull(value);
                return this;
            }

            public Builder setRemaining(String value) {
                values[9] = Objects.requireNonNull(value);
                return this;
            }

            public Builder setBuyerId(String value) {
                values[13] = Objects.requireNonNull(value);
                return this;
            }

            public Builder setSellerId(String value) {
                values[14] = Objects.requireNonNull(value);
                return this;
            }

            public Builder setError(String value) {
                values[15] = Objects.requireNonNull(value);
                return this;
            }

            public Builder setOrderId(long value) {
                values[8] = value;
                return this;
            }

            public Builder setTradeId(long value) {
                values[10] = value;
                return this;
            }

            public Builder setBuyOrderId(long value) {
                values[11] = value;
                return this;
            }

            public Builder setSellOrderId(long value) {
                values[12] = value;
                return this;
            }

            public Builder setNotificationId(long value) {
                values[18] = value;
                return this;
            }

            public Builder setCancelledCount(long value) {
                values[19] = value;
                return this;
            }

            public Builder setFound(boolean value) {
                values[16] = value;
                return this;
            }

            public Builder addItems(Packet value) {
                if (values[17] == null) {
                    values[17] = new ArrayList<Packet>();
                }
                items(values[17]).add(Objects.requireNonNull(value));
                return this;
            }

            public Builder addItems(Builder value) {
                return addItems(value.build());
            }

            public Packet build() {
                return new Packet(values);
            }
        }
    }
}
