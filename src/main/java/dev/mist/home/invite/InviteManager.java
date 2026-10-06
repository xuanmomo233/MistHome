package dev.mist.home.invite;

import dev.mist.home.MistHomePlugin;

import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 家园参观邀请（纯内存，带超时过期）。
 */
public class InviteManager {

    private record Invite(long homeId, UUID inviter, long expireAt) {
        boolean expired() {
            return System.currentTimeMillis() > expireAt;
        }
    }

    private final MistHomePlugin plugin;
    /** 被邀请人 -> 邀请信息（每人同时只持有一条有效邀请） */
    private final Map<UUID, Invite> invites = new ConcurrentHashMap<>();

    public InviteManager(MistHomePlugin plugin) {
        this.plugin = plugin;
    }

    public void invite(UUID invitee, long homeId, UUID inviter) {
        long expireAt = System.currentTimeMillis()
                + plugin.mistConfig().inviteExpireSeconds() * 1000L;
        invites.put(invitee, new Invite(homeId, inviter, expireAt));
    }

    /** 取出并消费邀请；过期或不存在返回 empty */
    public Optional<Long> accept(UUID invitee) {
        Invite invite = invites.remove(invitee);
        if (invite == null || invite.expired()) {
            return Optional.empty();
        }
        return Optional.of(invite.homeId());
    }

    public void deny(UUID invitee) {
        invites.remove(invitee);
    }

    public boolean hasInvite(UUID invitee) {
        Invite invite = invites.get(invitee);
        if (invite == null) {
            return false;
        }
        if (invite.expired()) {
            invites.remove(invitee);
            return false;
        }
        return true;
    }
}
