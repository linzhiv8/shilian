package com.shilian.infrastructure.mail;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.mail.MailException;
import org.springframework.mail.SimpleMailMessage;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.stereotype.Service;

/**
 * 发信。忘记密码重置和邮箱验证都走这里。
 *
 * <p><b>为什么对外只有一个 {@code send} 而用途由调用方拼。</b>
 * 曾经想过做成 {@code sendResetMail} / {@code sendVerifyMail} 两个方法，
 * 但那会把「邮件长什么样」和「什么时候发」焊在一起；
 * 而这两封信之后很可能要改文案、要加 HTML 版本，改的时候只该动一处。
 */
@Service
public class MailService {

    private static final Logger log = LoggerFactory.getLogger(MailService.class);

    private final JavaMailSender sender;
    private final String from;
    private final String publicUrl;

    public MailService(JavaMailSender sender,
                       @Value("${spring.mail.username:}") String from,
                       @Value("${shilian.public-url:http://127.0.0.1:5178}") String publicUrl) {
        this.sender = sender;
        this.from = from == null ? "" : from.trim();
        this.publicUrl = publicUrl.endsWith("/") ? publicUrl.substring(0, publicUrl.length() - 1) : publicUrl;
    }

    /**
     * 有没有配好发信账号。
     *
     * <p><b>为什么要有这个方法、以及为什么没配好就要让调用方知道。</b>
     * 发信失败却告诉用户「邮件已经发了」，用户会一直等一封永远不会来的信，
     * 而且他没有任何办法判断是「没发」还是「进了垃圾箱」。
     * 那比直接报错糟糕得多——报错至少让他知道该找人。
     */
    public boolean configured() {
        return !from.isEmpty();
    }

    /**
     * 发一封信。
     *
     * <p><b>抛异常而不是返回 boolean。</b>
     * 返回 false 的调用方很容易写成「记一行日志然后继续」，
     * 于是用户看到的是「已发送」。抛出去才能让上层决定是重试还是报错给用户。
     */
    public void send(String to, String subject, String body) {
        if (!configured()) {
            throw new MailNotConfiguredException();
        }
        SimpleMailMessage m = new SimpleMailMessage();
        /*
         * 发件人必须等于认证的那个账号：网易会校验一致性，
         * 填别的地址是 553 之类的拒信，而它的报错不点明是这一条，
         * 排查时会顺着「是不是内容触发了反垃圾」找很久。
         */
        m.setFrom(from);
        m.setTo(to);
        m.setSubject(subject);
        m.setText(body);
        try {
            sender.send(m);
        } catch (MailException e) {
            // 不吞：吞掉就等于告诉用户发成功了。只在这里补上上下文再抛。
            log.error("发信失败 to={} subject={}：{}", to, subject, e.toString());
            throw new MailSendFailedException();
        }
    }

    /** 重置密码的链接。指向前端页面，由前端拿 token 去调后端。 */
    public String resetLink(String token) {
        return publicUrl + "/reset?token=" + token;
    }

    /** 邮箱验证的链接。 */
    public String verifyLink(String token) {
        return publicUrl + "/verify?token=" + token;
    }

    /** 发信没配好。 */
    public static class MailNotConfiguredException extends RuntimeException {
        public MailNotConfiguredException() {
            super("还没配好发信账号");
        }
    }

    /** 配好了但没发出去。 */
    public static class MailSendFailedException extends RuntimeException {
        public MailSendFailedException() {
            super("邮件没发出去，稍后再试一次");
        }
    }
}
