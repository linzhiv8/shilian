package com.shilian.repo.handler;

import org.apache.ibatis.type.BaseTypeHandler;
import org.apache.ibatis.type.JdbcType;
import org.apache.ibatis.type.MappedTypes;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.sql.CallableStatement;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;

/**
 * {@code float[]} ↔ {@code MEDIUMBLOB}。用于 {@code link_embedding.vec}。
 *
 * <p>从原来的 {@code EmbeddingRepository.toBlob / fromBlob} 原样搬过来，
 * 包括那条最容易踩的规则：<b>必须显式指定小端。</b>
 *
 * <p>{@link ByteBuffer} 默认是 {@code BIG_ENDIAN}，而 x86 上 float 的内存序是小端。
 * 不指定的话，写进去和读出来只要用的是同一份代码就仍然自洽 —— 也就是说
 * <b>这个错误在纯 Java 的自测里永远不会暴露</b>。但只要换个语言去读同一列
 * （比如用 Node 写个脚本查库），就会得到一堆天文数字。
 */
@MappedTypes(float[].class)
public class FloatArrayTypeHandler extends BaseTypeHandler<float[]> {

    @Override
    public void setNonNullParameter(PreparedStatement ps, int i, float[] parameter,
                                    JdbcType jdbcType) throws SQLException {
        ps.setBytes(i, toBlob(parameter));
    }

    @Override
    public float[] getNullableResult(ResultSet rs, String columnName) throws SQLException {
        return fromBlob(rs.getBytes(columnName));
    }

    @Override
    public float[] getNullableResult(ResultSet rs, int columnIndex) throws SQLException {
        return fromBlob(rs.getBytes(columnIndex));
    }

    @Override
    public float[] getNullableResult(CallableStatement cs, int columnIndex) throws SQLException {
        return fromBlob(cs.getBytes(columnIndex));
    }

    public static byte[] toBlob(float[] vec) {
        ByteBuffer buf = ByteBuffer.allocate(vec.length * Float.BYTES).order(ByteOrder.LITTLE_ENDIAN);
        for (float v : vec) {
            buf.putFloat(v);
        }
        return buf.array();
    }

    /** null 返回空数组而不是 null，和原来一致 —— 调用方按「维度对不上」跳过它。 */
    public static float[] fromBlob(byte[] blob) {
        if (blob == null) {
            return new float[0];
        }
        ByteBuffer buf = ByteBuffer.wrap(blob).order(ByteOrder.LITTLE_ENDIAN);
        float[] vec = new float[blob.length / Float.BYTES];
        for (int i = 0; i < vec.length; i++) {
            vec[i] = buf.getFloat();
        }
        return vec;
    }
}
