package com.nowcoder.community.controller;

import com.google.code.kaptcha.Producer;
import com.nowcoder.community.entity.User;
import com.nowcoder.community.service.UserService;

import com.nowcoder.community.util.CommunityConstant;
import com.nowcoder.community.util.CommunityUtil;
import com.nowcoder.community.util.RedisKeyUtil;
import org.apache.commons.lang3.StringUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.RedisTemplate;

import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.CookieValue;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;

import javax.imageio.ImageIO;
import javax.servlet.http.Cookie;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import javax.servlet.http.HttpSession;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.io.OutputStream;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.TimeUnit;

@Controller
public class LoginController implements CommunityConstant {

    private static final Logger logger = LoggerFactory.getLogger(LoginController.class);
    @Autowired
    private UserService userService;

    @Autowired
    private Producer producer;

    @Autowired
    private RedisTemplate redisTemplate;

    @Value("${server.servlet.context-path}")
    private String contextPath;

    @GetMapping(value = "/register")
    public String getRegisterPage() {
        return "/site/register";
    }

    @GetMapping(value = "/login")
    public String getLoginPage() {
        return "/site/login";
    }
    /**
     * 用户名 邮箱 密码自动绑定
     *
     * @param model
     * @param user
     * @return
     */
    @PostMapping(value = "/register")
    public String register(Model model, User user) {
        Map<String, Object> map = userService.register(user);
        if (map == null || map.isEmpty()) {
            model.addAttribute("msg", "注册成功,我们已经向您的邮箱发送了一封激活邮件,请尽快激活!");
            model.addAttribute("target", "/index");
            return "/site/operate-result";
        } else {
            model.addAttribute("usernameMsg", map.get("usernameMsg"));
            model.addAttribute("passwordMsg", map.get("passwordMsg"));
            model.addAttribute("emailMsg", map.get("emailMsg"));
            return "/site/register";
        }
    }

    @GetMapping(value = "/activation/{userId}/{code}")
    public String activation(Model model, @PathVariable(value = "userId") int userId, @PathVariable(value = "code") String code) {
        int result = userService.activation(userId, code);
        if (result == 0) {
            model.addAttribute("msg", "激活成功，账号已可以正常使用！");
            model.addAttribute("target", "/login");
        } else if (result == 1) {
            model.addAttribute("msg", "重复操作，账号已被激活！");
            model.addAttribute("target", "/index");
        } else if (result == 2) {
            model.addAttribute("msg", "激活失败，激活码错误！");
            model.addAttribute("target", "/index");
        }
        return "/site/operate-result";
    }

    @GetMapping(value = "/kaptcha")
    public void checkCode(HttpServletResponse response/*, HttpSession session*/) {

        String code = producer.createText();
        BufferedImage image = producer.createImage(code);
        // 验证码的归属
        String kaptchaOwner = CommunityUtil.generateUUID();
        Cookie cookie = new Cookie("kaptchaOwner", kaptchaOwner);
        cookie.setMaxAge(60);
        cookie.setPath(contextPath);
        response.addCookie(cookie);
        String kaptchaKey = RedisKeyUtil.getKaptchaKey(kaptchaOwner);
        redisTemplate.opsForValue().set(kaptchaKey, code, 60, TimeUnit.SECONDS);

        // 将突图片输出给浏览器
        response.setContentType("image/png");
        try {
            OutputStream os = response.getOutputStream();
            ImageIO.write(image, "png", os);
        } catch (IOException e) {
            logger.error("响应验证码失败:" + e.getMessage());
        }

    }

    @PostMapping(value = "/login")
    public String login(String username, String password, String code, boolean rememberme,
                        @CookieValue(value = "kaptchaOwner") String kaptchaOwner, HttpServletResponse response, Model model) {
        Map<String, Object> map = new HashMap<>();
        String kaptchaValue = null;
        if (StringUtils.isNotBlank(kaptchaOwner)) {
            String redisKey = RedisKeyUtil.getKaptchaKey(kaptchaOwner);
            kaptchaValue = (String) redisTemplate.opsForValue().get(redisKey);
        }

        if (StringUtils.isBlank(kaptchaValue) || StringUtils.isBlank(code) || !kaptchaValue.equalsIgnoreCase(code)) {
            model.addAttribute("codeMsg", "验证码不正确!");
            return "/site/login";
        }

        //设置登录过期时间
        int expireSeconds = rememberme ? REMEMBER_EXPIRED_SECONDS : DEFAULT_EXPIRED_SECONDS;
        //登录
        map = userService.login(username, password, expireSeconds);
        if (map.containsKey("ticket")) {
            //设置Cookie
            Cookie cookie = new Cookie("ticket", (String) map.get("ticket"));
            cookie.setPath(contextPath);
            cookie.setMaxAge(expireSeconds);
            response.addCookie(cookie);
            return "redirect:/index";
        } else {
            model.addAttribute("usernameMsg", map.get("usernameMsg"));
            model.addAttribute("passwordMsg", map.get("passwordMsg"));
            return "/site/login";
        }
    }

    @GetMapping(value = "/logout")
    public String logout(@CookieValue("ticket") String ticket) {
        userService.logout(ticket);
        // SecurityContextHolder.clearContext();
        return "redirect:/login";
    }
}
