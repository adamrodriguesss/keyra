package com.rodrigues.keyra.service;

import com.rodrigues.keyra.dto.UpdateManagerRequest;
import com.rodrigues.keyra.entity.AccountStatus;
import com.rodrigues.keyra.entity.Manager;
import com.rodrigues.keyra.entity.ManagerRole;
import com.rodrigues.keyra.exception.ConflictException;
import com.rodrigues.keyra.repository.ManagerRepository;
import com.rodrigues.keyra.service.ManagerRealm.Actor;
import java.util.List;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Admin-side changes to manager accounts, on top of {@link ManagerRealm}: the instance must always keep an
 * active admin, and an admin can't lock themselves out. Callers must already have checked the admin role.
 */
@Service
public class ManagerService {

    private final ManagerRepository managers;
    private final ManagerRealm realm;

    public ManagerService(ManagerRepository managers, ManagerRealm realm) {
        this.managers = managers;
        this.realm = realm;
    }

    @Transactional(readOnly = true)
    public List<Manager> list() {
        return managers.findAllByOrderByCreatedAtAsc();
    }

    /** Partial update: a null field is left unchanged. */
    @Transactional
    public Manager update(Actor actor, UUID id, UpdateManagerRequest request) {
        List<Manager> activeAdmins = lockActiveAdmins();
        Manager manager = realm.find(id);
        boolean wasActiveAdmin = manager.isActiveAdmin();
        if (request.role() != null) {
            manager.setRole(request.role());
        }
        if (request.status() != null) {
            realm.setStatus(id, request.status());
        }
        if (wasActiveAdmin && !manager.isActiveAdmin()) {
            requireAnotherAdmin(actor, manager, activeAdmins);
        }
        return manager;
    }

    @Transactional
    public void delete(Actor actor, UUID id) {
        List<Manager> activeAdmins = lockActiveAdmins();
        Manager manager = realm.find(id);
        if (manager.isActiveAdmin()) {
            requireAnotherAdmin(actor, manager, activeAdmins);
        }
        realm.delete(manager);
    }

    /** Taken before loading the target, so the target's state is read under the lock too. */
    private List<Manager> lockActiveAdmins() {
        return managers.findByRoleAndStatus(ManagerRole.ADMIN, AccountStatus.ACTIVE);
    }

    private static void requireAnotherAdmin(Actor actor, Manager removed, List<Manager> activeAdmins) {
        if (removed.getId().equals(actor.id())) {
            throw new ConflictException("You cannot disable, demote or delete your own account.");
        }
        if (activeAdmins.size() == 1) {
            throw new ConflictException("There must be at least one active admin.");
        }
    }
}
