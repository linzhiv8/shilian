package com.shilian.repo.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.shilian.repo.handler.FloatArrayTypeHandler;

/**
 * {@code link_embedding} 表的实体：向量缓存。
 *
 * <p>这张表<b>刻意加了外键级联</b>（和 {@code correction} 正好相反）：
 * 向量是链接的派生属性，不是用户表达过的偏好。链接没了向量就该跟着没，
 * 否则会攒一堆孤儿行，而且下次搜索还会把它们算进去。
 *
 * <p>用级联而不是在删除逻辑里手写一句，是因为手写的那句迟早会有人忘。
 * 而级联只在 InnoDB 上生效 —— 这也是 DDL 里每张表都显式写
 * {@code ENGINE=InnoDB} 的原因，MySQL 对非 InnoDB 表会静默忽略 FOREIGN KEY。
 */
@TableName("link_embedding")
public class LinkEmbeddingEntity {

    @TableId(value = "link_id", type = IdType.INPUT)
    private String linkId;

    private String model;

    /** 维度。换模型（或同模型换维度）之后新旧向量不在同一空间，靠它识别。 */
    private Integer dim;

    /** float32 小端二进制，长度 = dim × 4 字节。转换见 {@link FloatArrayTypeHandler}。 */
    @TableField(typeHandler = FloatArrayTypeHandler.class)
    private float[] vec;

    /** 拼好的可检索文本的 SHA-256。对不上才重算，见 {@code SemanticSearchService.searchTextOf}。 */
    private String textHash;

    private String updatedAt;

    public String getLinkId() { return linkId; }
    public void setLinkId(String linkId) { this.linkId = linkId; }

    public String getModel() { return model; }
    public void setModel(String model) { this.model = model; }

    public Integer getDim() { return dim; }
    public void setDim(Integer dim) { this.dim = dim; }

    public float[] getVec() { return vec; }
    public void setVec(float[] vec) { this.vec = vec; }

    public String getTextHash() { return textHash; }
    public void setTextHash(String textHash) { this.textHash = textHash; }

    public String getUpdatedAt() { return updatedAt; }
    public void setUpdatedAt(String updatedAt) { this.updatedAt = updatedAt; }
}
