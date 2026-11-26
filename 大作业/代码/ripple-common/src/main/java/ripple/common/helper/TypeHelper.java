// Copyright (c) 2023 Institute of Software, Chinese Academy of Sciences
// Ripple is licensed under Mulan PSL v2.
// You can use this software according to the terms and conditions of the Mulan PSL v2.
// You may obtain a copy of Mulan PSL v2 at:
//          http://license.coscl.org.cn/MulanPSL2
// THIS SOFTWARE IS PROVIDED ON AN "AS IS" BASIS, WITHOUT WARRANTIES OF ANY KIND,
// EITHER EXPRESS OR IMPLIED, INCLUDING BUT NOT LIMITED TO NON-INFRINGEMENT,
// MERCHANTABILITY OR FIT FOR A PARTICULAR PURPOSE.
// See the Mulan PSL v2 for more details.

package ripple.common.helper;

import io.netty.buffer.ByteBuf;
import ripple.common.entity.Constants;

import java.nio.charset.StandardCharsets;
import java.util.Date;
import java.util.UUID;

/**
 * @author Zhen Tang
 */
public final class TypeHelper {
    private static final byte ENUM_FALLBACK_TAG = (byte) 0xFF;

    private static final String[] OPERATION_TYPE_TABLE = {
        Constants.MESSAGE_TYPE_UPDATE,
        Constants.MESSAGE_TYPE_DELETE,
        Constants.MESSAGE_TYPE_INCREMENTAL_UPDATE
    };

    private static final String[] ATOMIC_OPERATION_TABLE = {
        Constants.ATOMIC_OPERATION_ADD_ENTRY,
        Constants.ATOMIC_OPERATION_REMOVE_ENTRY
    };

    private TypeHelper() {

    }

    public static UUID readUuid(ByteBuf byteBuf) {
        return new UUID(byteBuf.readLong(), byteBuf.readLong());
    }

    public static void writeUuid(UUID value, ByteBuf byteBuf) {
        byteBuf.writeLong(value.getMostSignificantBits());
        byteBuf.writeLong(value.getLeastSignificantBits());
    }

    public static int readVarInt(ByteBuf byteBuf) {
        int value = 0;
        int shift = 0;
        while (true) {
            byte b = byteBuf.readByte();
            value |= (b & 0x7F) << shift;
            if ((b & 0x80) == 0) {
                return value;
            }
            shift += 7;
            if (shift >= 35) {
                throw new IllegalStateException("Malformed VarInt");
            }
        }
    }

    public static void writeVarInt(int value, ByteBuf byteBuf) {
        if (value < 0) {
            throw new IllegalArgumentException("value must be non-negative: " + value);
        }
        while ((value & ~0x7F) != 0) {
            byteBuf.writeByte((value & 0x7F) | 0x80);
            value >>>= 7;
        }
        byteBuf.writeByte(value);
    }

    public static int readSVarInt(ByteBuf byteBuf) {
        int raw = readVarInt(byteBuf);
        return (raw >>> 1) ^ -(raw & 1);
    }

    public static void writeSVarInt(int value, ByteBuf byteBuf) {
        int encoded = (value << 1) ^ (value >> 31);
        while ((encoded & ~0x7F) != 0) {
            byteBuf.writeByte((encoded & 0x7F) | 0x80);
            encoded >>>= 7;
        }
        byteBuf.writeByte(encoded);
    }

    public static long readVarLong(ByteBuf byteBuf) {
        long value = 0L;
        int shift = 0;
        while (true) {
            byte b = byteBuf.readByte();
            value |= (long) (b & 0x7F) << shift;
            if ((b & 0x80) == 0) {
                return value;
            }
            shift += 7;
            if (shift >= 70) {
                throw new IllegalStateException("Malformed VarLong");
            }
        }
    }

    public static void writeVarLong(long value, ByteBuf byteBuf) {
        while ((value & ~0x7FL) != 0L) {
            byteBuf.writeByte(((int) value & 0x7F) | 0x80);
            value >>>= 7;
        }
        byteBuf.writeByte((int) value);
    }

    public static long readSVarLong(ByteBuf byteBuf) {
        long raw = readVarLong(byteBuf);
        return (raw >>> 1) ^ -(raw & 1L);
    }

    public static void writeSVarLong(long value, ByteBuf byteBuf) {
        writeVarLong((value << 1) ^ (value >> 63), byteBuf);
    }

    public static Date readTimestamp(ByteBuf byteBuf) {
        return new Date(readSVarLong(byteBuf));
    }

    public static void writeTimestamp(Date value, ByteBuf byteBuf) {
        if (value == null) {
            throw new NullPointerException("value");
        }
        writeSVarLong(value.getTime(), byteBuf);
    }

    public static String readString(ByteBuf byteBuf) {
        int size = readVarInt(byteBuf);
        byte[] bytes = new byte[size];
        byteBuf.readBytes(bytes);
        return new String(bytes, StandardCharsets.UTF_8);
    }

    public static void writeString(String value, ByteBuf byteBuf) {
        if (value == null) {
            throw new NullPointerException("value");
        }
        byte[] bytes = value.getBytes(StandardCharsets.UTF_8);
        writeVarInt(bytes.length, byteBuf);
        byteBuf.writeBytes(bytes);
    }

    public static String readOperationType(ByteBuf byteBuf) {
        return readEnumString(byteBuf, OPERATION_TYPE_TABLE);
    }

    public static void writeOperationType(String operationType, ByteBuf byteBuf) {
        writeEnumString(operationType, byteBuf, OPERATION_TYPE_TABLE, "operationType");
    }

    public static String readAtomicOperation(ByteBuf byteBuf) {
        return readEnumString(byteBuf, ATOMIC_OPERATION_TABLE);
    }

    public static void writeAtomicOperation(String atomicOperation, ByteBuf byteBuf) {
        writeEnumString(atomicOperation, byteBuf, ATOMIC_OPERATION_TABLE, "atomicOperation");
    }

    private static String readEnumString(ByteBuf byteBuf, String[] table) {
        int tag = byteBuf.readUnsignedByte();
        if (tag == (ENUM_FALLBACK_TAG & 0xFF)) {
            return readString(byteBuf);
        }
        if (tag >= table.length) {
            throw new IllegalStateException("Unknown enum tag: " + tag);
        }
        return table[tag];
    }

    private static void writeEnumString(String value, ByteBuf byteBuf, String[] table, String paramName) {
        if (value == null) {
            throw new NullPointerException(paramName);
        }
        for (int i = 0; i < table.length; i++) {
            if (table[i].equals(value)) {
                byteBuf.writeByte(i);
                return;
            }
        }
        byteBuf.writeByte(ENUM_FALLBACK_TAG);
        writeString(value, byteBuf);
    }

    public static boolean readBoolean(ByteBuf byteBuf) {
        byte value = byteBuf.readByte();
        return value == 1 ? true : false;
    }

    public static void writeBoolean(boolean value, ByteBuf byteBuf) {
        byteBuf.writeByte(value ? (byte) 1 : (byte) 0);
    }
}
