package com.shilian.repo.handler;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.apache.ibatis.type.BaseTypeHandler;
import org.apache.ibatis.type.JdbcType;
import org.apache.ibatis.type.MappedTypes;

import java.sql.CallableStatement;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.List;

/**
 * {@code List<String>} ↔ JSON 数组文本。
 *
 * <p>库里三列是这种格式：{@code link.note_options}、{@code link.purpose_categories}、
 * {@code link.tags}。它们在 DDL 里是 {@code TEXT}，存的是一段 JSON。
 *
 * <p>为什么不引 {@code mybatis-plus-json} 那套现成的：它默认会去认 MySQL 的
 * {@code JSON} 列类型，而这三列是 {@code TEXT}——声明成 JSON 列的话，
 * 现成方案会尝试把列类型也当成 JSON 处理，反而绕。这里只需要「存取时转一下」，
 * 三十行够了。
 *
 * <p><b>解析失败时静默返回空列表，这是从原来的 {@code LinkRepository.jsonList()}
 * 原样搬过来的行为</b>（那边也是 {@code catch (Exception) { return List.of(); }}）。
 * 迁移的约定是零行为变化，所以这里不改。但要知道代价：标签解析失败的表现是
 * 「标签凭空消失」，而日志里一个字都没有。修它属于另一个改动
 * （至少补一行 warn，带上记录 id 和原始字符串）。
 */
@MappedTypes(List.class)
public class JsonListTypeHandler extends BaseTypeHandler<List<String>> {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private static final TypeReference<List<String>> TYPE = new TypeReference<>() {};

    @Override
    public void setNonNullParameter(PreparedStatement ps, int i, List<String> parameter,
                                    JdbcType jdbcType) throws SQLException {
        ps.setString(i, toJson(parameter));
    }

    @Override
    public List<String> getNullableResult(ResultSet rs, String columnName) throws SQLException {
        return fromJson(rs.getString(columnName));
    }

    @Override
    public List<String> getNullableResult(ResultSet rs, int columnIndex) throws SQLException {
        return fromJson(rs.getString(columnIndex));
    }

    @Override
    public List<String> getNullableResult(CallableStatement cs, int columnIndex) throws SQLException {
        return fromJson(cs.getString(columnIndex));
    }

    /** 和原来 {@code json()} 一致：null 写成 {@code []}，序列化失败也返回 {@code []}。 */
    public static String toJson(List<String> list) {
        try {
            return MAPPER.writeValueAsString(list == null ? List.of() : list);
        } catch (Exception e) {
            return "[]";
        }
    }

    /** 和原来 {@code jsonList()} 一致：空/null/解析失败一律返回空列表。 */
    public static List<String> fromJson(String raw) {
        if (raw == null || raw.isBlank()) {
            return List.of();
        }
        try {
            return MAPPER.readValue(raw, TYPE);
        } catch (Exception e) {
            return List.of();
        }
    }
}
