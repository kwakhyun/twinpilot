package io.twinpilot.api.security;

import java.util.Set;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** 현재 사용자와 실효 역할. 프론트엔드가 메뉴·버튼 노출을 정할 때 쓴다(권한 판단은 서버가 다시 한다). */
@RestController
@RequestMapping("/api/v1/me")
public class MeController {

    private final CurrentUser currentUser;

    public MeController(CurrentUser currentUser) {
        this.currentUser = currentUser;
    }

    public record Me(String subject, String username, Set<String> roles) {
    }

    @GetMapping
    public Me me() {
        Actor actor = currentUser.get();
        return new Me(actor.subject(), actor.username(), actor.roles());
    }
}
