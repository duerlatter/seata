/*
 *  Copyright 1999-2019 Seata.io Group.
 *
 *  Licensed under the Apache License, Version 2.0 (the "License");
 *  you may not use this file except in compliance with the License.
 *  You may obtain a copy of the License at
 *
 *       http://www.apache.org/licenses/LICENSE-2.0
 *
 *  Unless required by applicable law or agreed to in writing, software
 *  distributed under the License is distributed on an "AS IS" BASIS,
 *  WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *  See the License for the specific language governing permissions and
 *  limitations under the License.
 */
package io.seata.server.store;

import java.io.File;

import io.seata.common.XID;
import io.seata.config.Configuration;
import io.seata.config.ConfigurationFactory;
import io.seata.core.constants.ConfigurationKeys;
import io.seata.core.model.BranchStatus;
import io.seata.core.model.BranchType;
import io.seata.core.model.GlobalStatus;
import io.seata.server.lock.LockManager;
import io.seata.server.lock.file.FileLockManagerForTest;
import io.seata.server.session.BranchSession;
import io.seata.server.session.GlobalSession;
import io.seata.server.session.SessionHelper;
import io.seata.server.session.SessionHolder;
import io.seata.server.store.StoreConfig.SessionMode;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationContext;

import static io.seata.common.DefaultValues.DEFAULT_TX_GROUP;

/**
 * The type Session store test.
 */
@SpringBootTest
public class SessionStoreTest {

    @BeforeAll
    public static void setUp(ApplicationContext context) {

    }

    /**
     * The constant RESOURCE_ID.
     */
    public static final String RESOURCE_ID = "mysql:xxx";

    private static Configuration CONFIG = ConfigurationFactory.getInstance();

    /**
     * Clean.
     *
     * @throws Exception the exception
     */
    @BeforeEach
    public void clean() throws Exception {
        String sessionStorePath = CONFIG.getConfig(ConfigurationKeys.STORE_FILE_DIR);
        File rootDataFile = new File(sessionStorePath + File.separator + SessionHolder.ROOT_SESSION_MANAGER_NAME);
        File rootDataFileHis = new File(
            sessionStorePath + File.separator + SessionHolder.ROOT_SESSION_MANAGER_NAME + ".1");

        if (rootDataFile.exists()) {
            rootDataFile.delete();
        }
        if (rootDataFileHis.exists()) {
            rootDataFileHis.delete();
        }
        LockManager lockManager = new FileLockManagerForTest();
        lockManager.cleanAllLocks();
    }

    /**
     * Test restored from file.
     *
     * @throws Exception the exception
     */
    @Test
    public void testRestoredFromFile() throws Exception {
        try {
            SessionHolder.init(SessionMode.FILE);
            GlobalSession globalSession = new GlobalSession("demo-app", DEFAULT_TX_GROUP, "test", 6000);
            String xid = XID.generateXID(globalSession.getTransactionId());
            globalSession.setXid(xid);

            globalSession.addSessionLifecycleListener(SessionHolder.getRootSessionManager());
            globalSession.begin();

            BranchSession branchSession1 = SessionHelper.newBranchByGlobal(globalSession, BranchType.AT, RESOURCE_ID,
                "ta:1,2;tb:3", "xxx");
            branchSession1.setXid(xid);
            branchSession1.lock();
            globalSession.addBranch(branchSession1);

            LockManager lockManager = new FileLockManagerForTest();

            String otherXID = XID.generateXID(0L);

            Assertions.assertFalse(lockManager.isLockable(otherXID, RESOURCE_ID, "ta:1"));
            Assertions.assertFalse(lockManager.isLockable(otherXID, RESOURCE_ID, "ta:2"));
            Assertions.assertFalse(lockManager.isLockable(otherXID, RESOURCE_ID, "tb:3"));

            Assertions.assertTrue(lockManager.isLockable(otherXID, RESOURCE_ID, "ta:4"));
            Assertions.assertTrue(lockManager.isLockable(otherXID, RESOURCE_ID, "tb:5"));

            lockManager.cleanAllLocks();

            Assertions.assertTrue(lockManager.isLockable(otherXID, RESOURCE_ID, "ta:1"));
            Assertions.assertTrue(lockManager.isLockable(otherXID, RESOURCE_ID, "ta:2"));
            Assertions.assertTrue(lockManager.isLockable(otherXID, RESOURCE_ID, "tb:3"));

            // Re-init SessionHolder: restore sessions from file
            SessionHolder.init(SessionMode.FILE);

            long tid = globalSession.getTransactionId();
            GlobalSession reloadSession = SessionHolder.findGlobalSession(globalSession.getXid());
            Assertions.assertNotNull(reloadSession);
            Assertions.assertNotSame(globalSession, reloadSession);
            Assertions.assertEquals(globalSession.getApplicationId(), reloadSession.getApplicationId());

            Assertions.assertFalse(lockManager.isLockable(otherXID, RESOURCE_ID, "ta:1"));
            Assertions.assertFalse(lockManager.isLockable(otherXID, RESOURCE_ID, "ta:2"));
            Assertions.assertFalse(lockManager.isLockable(otherXID, RESOURCE_ID, "tb:3"));
            Assertions.assertTrue(lockManager.isLockable(xid, RESOURCE_ID, "tb:3"));

            //clear
            reloadSession.addSessionLifecycleListener(SessionHolder.getRootSessionManager());
            reloadSession.end();
        } finally {
            SessionHolder.destroy();
        }
    }

    /**
     * Test restored from file 2.
     *
     * @throws Exception the exception
     */
    //@Test
    public void testRestoredFromFile2() throws Exception {
        try {
            SessionHolder.init(SessionMode.FILE);
            GlobalSession globalSession = new GlobalSession("demo-app", DEFAULT_TX_GROUP, "test", 6000);

            globalSession.addSessionLifecycleListener(SessionHolder.getRootSessionManager());
            globalSession.begin();

            // Re-init SessionHolder: restore sessions from file
            SessionHolder.init(SessionMode.FILE);
        } finally {
            SessionHolder.destroy();
        }
    }

    /**
     * Test restored from file async committing.
     *
     * @throws Exception the exception
     */
    @Test
    public void testRestoredFromFileAsyncCommitting() throws Exception {
        try {
            SessionHolder.init(SessionMode.FILE);
            GlobalSession globalSession = new GlobalSession("demo-app", DEFAULT_TX_GROUP, "test", 6000);

            String xid = XID.generateXID(globalSession.getTransactionId());
            globalSession.setXid(xid);

            globalSession.addSessionLifecycleListener(SessionHolder.getRootSessionManager());
            globalSession.begin();

            BranchSession branchSession1 = SessionHelper.newBranchByGlobal(globalSession, BranchType.AT, RESOURCE_ID,
                "ta:1", "xxx");
            Assertions.assertTrue(branchSession1.lock());
            globalSession.addBranch(branchSession1);

            LockManager lockManager = new FileLockManagerForTest();

            String otherXID = XID.generateXID(0L);

            Assertions.assertFalse(lockManager.isLockable(otherXID, RESOURCE_ID, "ta:1"));

            globalSession.changeGlobalStatus(GlobalStatus.AsyncCommitting);

            lockManager.cleanAllLocks();

            Assertions.assertTrue(lockManager.isLockable(otherXID, RESOURCE_ID, "ta:1"));

            // Re-init SessionHolder: restore sessions from file
            SessionHolder.init(SessionMode.FILE);

            long tid = globalSession.getTransactionId();
            GlobalSession reloadSession = SessionHolder.findGlobalSession(globalSession.getXid());
            Assertions.assertEquals(reloadSession.getStatus(), GlobalStatus.AsyncCommitting);

            GlobalSession sessionInAsyncCommittingQueue = SessionHolder.getAsyncCommittingSessionManager()
                .findGlobalSession(globalSession.getXid());
            Assertions.assertSame(reloadSession, sessionInAsyncCommittingQueue);

            // No locking for session in AsyncCommitting status
            Assertions.assertTrue(lockManager.isLockable(otherXID, RESOURCE_ID, "ta:1"));

            //clear
            reloadSession.addSessionLifecycleListener(SessionHolder.getRootSessionManager());
            reloadSession.end();
        } finally {
            SessionHolder.destroy();
        }
    }

    /**
     * Test restored from file commit retry.
     *
     * @throws Exception the exception
     */
    @Test
    public void testRestoredFromFileCommitRetry() throws Exception {
        try {
            SessionHolder.init(SessionMode.FILE);
            GlobalSession globalSession = new GlobalSession("demo-app", DEFAULT_TX_GROUP, "test", 6000);

            String xid = XID.generateXID(globalSession.getTransactionId());
            globalSession.setXid(xid);

            globalSession.addSessionLifecycleListener(SessionHolder.getRootSessionManager());
            globalSession.begin();

            BranchSession branchSession1 = SessionHelper.newBranchByGlobal(globalSession, BranchType.AT, RESOURCE_ID,
                "ta:1", "xxx");
            branchSession1.lock();
            globalSession.addBranch(branchSession1);

            LockManager lockManager = new FileLockManagerForTest();

            String otherXID = XID.generateXID(0L);

            Assertions.assertFalse(lockManager.isLockable(otherXID, RESOURCE_ID, "ta:1"));

            globalSession.changeGlobalStatus(GlobalStatus.Committing);
            globalSession.changeBranchStatus(branchSession1, BranchStatus.PhaseTwo_CommitFailed_Retryable);
            globalSession.changeGlobalStatus(GlobalStatus.CommitRetrying);

            lockManager.cleanAllLocks();

            Assertions.assertTrue(lockManager.isLockable(otherXID, RESOURCE_ID, "ta:1"));

            // Re-init SessionHolder: restore sessions from file
            SessionHolder.init(SessionMode.FILE);

            long tid = globalSession.getTransactionId();
            GlobalSession reloadSession = SessionHolder.findGlobalSession(globalSession.getXid());
            Assertions.assertEquals(reloadSession.getStatus(), GlobalStatus.CommitRetrying);

            GlobalSession sessionInRetryCommittingQueue = SessionHolder.getRetryCommittingSessionManager()
                .findGlobalSession(globalSession.getXid());
            Assertions.assertSame(reloadSession, sessionInRetryCommittingQueue);
            BranchSession reloadBranchSession = reloadSession.getBranch(branchSession1.getBranchId());
            Assertions.assertEquals(reloadBranchSession.getStatus(), BranchStatus.PhaseTwo_CommitFailed_Retryable);

            // CommitRetrying status will never hold the lock
            Assertions.assertTrue(lockManager.isLockable(otherXID, RESOURCE_ID, "ta:1"));

            //clear
            reloadSession.addSessionLifecycleListener(SessionHolder.getRootSessionManager());
            reloadSession.end();
        } finally {
            SessionHolder.destroy();
        }
    }

    /**
     * Test restored from file rollback retry.
     *
     * @throws Exception the exception
     */
    @Test
    public void testRestoredFromFileRollbackRetry() throws Exception {
        try {
            SessionHolder.init(SessionMode.FILE);

            GlobalSession globalSession = new GlobalSession("demo-app", DEFAULT_TX_GROUP, "test", 6000);

            String xid = XID.generateXID(globalSession.getTransactionId());
            globalSession.setXid(xid);

            globalSession.addSessionLifecycleListener(SessionHolder.getRootSessionManager());
            globalSession.begin();

            BranchSession branchSession1 = SessionHelper.newBranchByGlobal(globalSession, BranchType.AT, RESOURCE_ID,
                "ta:1", "xxx");
            branchSession1.lock();
            globalSession.addBranch(branchSession1);

            LockManager lockManager = new FileLockManagerForTest();

            String otherXID = XID.generateXID(0L);

            Assertions.assertFalse(lockManager.isLockable(otherXID, RESOURCE_ID, "ta:1"));

            globalSession.changeGlobalStatus(GlobalStatus.Rollbacking);
            globalSession.changeBranchStatus(branchSession1, BranchStatus.PhaseTwo_RollbackFailed_Retryable);
            globalSession.changeGlobalStatus(GlobalStatus.RollbackRetrying);

            lockManager.cleanAllLocks();

            Assertions.assertTrue(lockManager.isLockable(otherXID, RESOURCE_ID, "ta:1"));

            // Re-init SessionHolder: restore sessions from file
            SessionHolder.init(SessionMode.FILE);

            long tid = globalSession.getTransactionId();
            GlobalSession reloadSession = SessionHolder.findGlobalSession(globalSession.getXid());
            Assertions.assertEquals(reloadSession.getStatus(), GlobalStatus.RollbackRetrying);

            GlobalSession sessionInRetryRollbackingQueue = SessionHolder.getRetryRollbackingSessionManager()
                .findGlobalSession(globalSession.getXid());
            Assertions.assertSame(reloadSession, sessionInRetryRollbackingQueue);
            BranchSession reloadBranchSession = reloadSession.getBranch(branchSession1.getBranchId());
            Assertions.assertEquals(reloadBranchSession.getStatus(), BranchStatus.PhaseTwo_RollbackFailed_Retryable);

            // Lock is held by session in RollbackRetrying status
            Assertions.assertFalse(lockManager.isLockable(otherXID, RESOURCE_ID, "ta:1"));

            //clear
            reloadSession.addSessionLifecycleListener(SessionHolder.getRootSessionManager());
            reloadSession.end();
        } finally {
            SessionHolder.destroy();
        }
    }

    /**
     * Test restored from file rollback failed.
     *
     * @throws Exception the exception
     */
    @Test
    public void testRestoredFromFileRollbackFailed() throws Exception {
        try {
            SessionHolder.init(SessionMode.FILE);

            GlobalSession globalSession = new GlobalSession("demo-app", DEFAULT_TX_GROUP, "test", 6000);

            String xid = XID.generateXID(globalSession.getTransactionId());
            globalSession.setXid(xid);

            globalSession.addSessionLifecycleListener(SessionHolder.getRootSessionManager());
            globalSession.begin();

            BranchSession branchSession1 = SessionHelper.newBranchByGlobal(globalSession, BranchType.AT, RESOURCE_ID,
                "ta:1", "xxx");
            branchSession1.lock();
            globalSession.addBranch(branchSession1);

            LockManager lockManager = new FileLockManagerForTest();

            String otherXID = XID.generateXID(0L);

            Assertions.assertFalse(lockManager.isLockable(otherXID, RESOURCE_ID, "ta:1"));

            globalSession.changeGlobalStatus(GlobalStatus.Rollbacking);
            globalSession.changeBranchStatus(branchSession1, BranchStatus.PhaseTwo_CommitFailed_Unretryable);
            SessionHelper.endRollbackFailed(globalSession, false);

            // Lock is released.
            Assertions.assertFalse(lockManager.isLockable(otherXID, RESOURCE_ID, "ta:1"));

            lockManager.cleanAllLocks();

            Assertions.assertTrue(lockManager.isLockable(otherXID, RESOURCE_ID, "ta:1"));

            // Re-init SessionHolder: restore sessions from file
            SessionHolder.init(SessionMode.FILE);

            long tid = globalSession.getTransactionId();
            GlobalSession reloadSession = SessionHolder.findGlobalSession(globalSession.getXid());
            Assertions.assertNull(reloadSession);
        } finally {
            SessionHolder.destroy();
        }
    }
}
