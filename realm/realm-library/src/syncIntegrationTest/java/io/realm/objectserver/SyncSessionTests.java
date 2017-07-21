package io.realm.objectserver;

import android.os.SystemClock;
import android.support.test.runner.AndroidJUnit4;

import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import io.realm.BaseIntegrationTest;
import io.realm.Realm;
import io.realm.SyncConfiguration;
import io.realm.SyncManager;
import io.realm.SyncSession;
import io.realm.SyncUser;
import io.realm.TestHelper;
import io.realm.entities.AllTypes;
import io.realm.objectserver.utils.Constants;
import io.realm.objectserver.utils.UserFactory;
import io.realm.rule.TestSyncConfigurationFactory;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

@RunWith(AndroidJUnit4.class)
public class SyncSessionTests extends BaseIntegrationTest {
    @Rule
    public TestSyncConfigurationFactory configFactory = new TestSyncConfigurationFactory();

    @Test
    public void getState_active() {
        SyncUser user = UserFactory.createUniqueUser(Constants.AUTH_URL);
        SyncConfiguration syncConfiguration = configFactory
                .createSyncConfigurationBuilder(user, Constants.SYNC_SERVER_URL)
                .waitForInitialRemoteData()
                .build();
        Realm realm = Realm.getInstance(syncConfiguration);

        SyncSession session = SyncManager.getSession(syncConfiguration);

        // make sure the `access_token` is acquired. otherwise we can still be
        // in WAITING_FOR_ACCESS_TOKEN state
        SystemClock.sleep(TimeUnit.SECONDS.toMillis(2));

        assertEquals(SyncSession.State.ACTIVE, session.getState());
        realm.close();
    }

    @Test
    public void getState_inactive() {
        SyncUser user = UserFactory.createUniqueUser(Constants.AUTH_URL);
        SyncConfiguration syncConfiguration = configFactory
                .createSyncConfigurationBuilder(user, Constants.SYNC_SERVER_URL)
                .waitForInitialRemoteData()
                .build();
        Realm realm = Realm.getInstance(syncConfiguration);

        SyncSession session = SyncManager.getSession(syncConfiguration);
        user.logout();
        assertEquals(SyncSession.State.INACTIVE, session.getState());

        realm.close();
    }

    @Test
    public void getState_closedRealm() {
        SyncUser user = UserFactory.createUniqueUser(Constants.AUTH_URL);
        SyncConfiguration syncConfiguration = configFactory
                .createSyncConfigurationBuilder(user, Constants.SYNC_SERVER_URL)
                .waitForInitialRemoteData()
                .build();
        Realm realm = Realm.getInstance(syncConfiguration);

        SyncSession session = SyncManager.getSession(syncConfiguration);
        realm.close();
        try {
            session.getState();
            fail("Realm was closed, getState should not return");
        } catch (IllegalStateException expected) {
        }
    }

    @Test
    public void uploadDownloadAllChanges() throws InterruptedException {
        SyncUser user = UserFactory.createUniqueUser(Constants.AUTH_URL);
        SyncUser adminUser = UserFactory.createAdminUser(Constants.AUTH_URL);
        SyncConfiguration userConfig = configFactory
                .createSyncConfigurationBuilder(user, Constants.SYNC_SERVER_URL)
                .build();
        SyncConfiguration adminConfig = configFactory
                .createSyncConfigurationBuilder(adminUser, userConfig.getServerUrl().toString())
                .build();

        Realm userRealm = Realm.getInstance(userConfig);
        userRealm.beginTransaction();
        userRealm.createObject(AllTypes.class);
        userRealm.commitTransaction();
        SyncManager.getSession(userConfig).uploadAllLocalChanges();
        userRealm.close();

        Realm adminRealm = Realm.getInstance(adminConfig);
        SyncManager.getSession(adminConfig).downloadAllServerChanges();
        adminRealm.refresh();
        assertEquals(1, adminRealm.where(AllTypes.class).count());
        adminRealm.close();
    }

    @Test
    public void interruptWaits() throws InterruptedException {
        final SyncUser user = UserFactory.createUniqueUser(Constants.AUTH_URL);
        SyncUser adminUser = UserFactory.createAdminUser(Constants.AUTH_URL);
        final SyncConfiguration userConfig = configFactory
                .createSyncConfigurationBuilder(user, Constants.SYNC_SERVER_URL)
                .build();
        final SyncConfiguration adminConfig = configFactory
                .createSyncConfigurationBuilder(adminUser, userConfig.getServerUrl().toString())
                .build();

        Thread t = new Thread(new Runnable() {
            @Override
            public void run() {
                Realm userRealm = Realm.getInstance(userConfig);
                userRealm.beginTransaction();
                userRealm.createObject(AllTypes.class);
                userRealm.commitTransaction();
                SyncSession userSession = SyncManager.getSession(userConfig);
                try {
                    // 1. Start download (which will be interrupted)
                    Thread.currentThread().interrupt();
                    userSession.downloadAllServerChanges();
                } catch (InterruptedException ignored) {
                    assertFalse(Thread.currentThread().isInterrupted());
                }
                try {
                    // 2. Upload all changes
                    userSession.uploadAllLocalChanges();
                } catch (InterruptedException e) {
                    fail("Upload interrupted");
                }
                userRealm.close();

                Realm adminRealm = Realm.getInstance(adminConfig);
                SyncSession adminSession = SyncManager.getSession(adminConfig);
                try {
                    // 3. Start upload (which will be interrupted)
                    Thread.currentThread().interrupt();
                    adminSession.uploadAllLocalChanges();
                } catch (InterruptedException ignored) {
                    assertFalse(Thread.currentThread().isInterrupted()); // clear interrupted flag
                }
                try {
                    // 4. Download all changes
                    adminSession.downloadAllServerChanges();
                } catch (InterruptedException e) {
                    fail("Download interrupted");
                }
                adminRealm.refresh();
                assertEquals(1, adminRealm.where(AllTypes.class).count());
                adminRealm.close();
            }
        });
        t.start();
        t.join();
    }
}
