// Copyright (C) 2017 The Android Open Source Project
//
// Licensed under the Apache License, Version 2.0 (the "License");
// you may not use this file except in compliance with the License.
// You may obtain a copy of the License at
//
// http://www.apache.org/licenses/LICENSE-2.0
//
// Unless required by applicable law or agreed to in writing, software
// distributed under the License is distributed on an "AS IS" BASIS,
// WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
// See the License for the specific language governing permissions and
// limitations under the License.

package com.google.gerrit.acceptance.rest.account;

import static com.google.common.truth.Truth.assertThat;
import static com.google.gerrit.acceptance.GitUtil.fetch;
import static com.google.gerrit.server.account.externalids.ExternalId.SCHEME_MAILTO;
import static com.google.gerrit.server.account.externalids.ExternalId.SCHEME_USERNAME;
import static com.google.gerrit.server.group.SystemGroupBackend.REGISTERED_USERS;
import static org.junit.Assert.fail;

import com.github.rholder.retry.BlockStrategy;
import com.github.rholder.retry.Retryer;
import com.github.rholder.retry.RetryerBuilder;
import com.github.rholder.retry.StopStrategies;
import com.google.common.collect.ImmutableList;
import com.google.gerrit.acceptance.AbstractDaemonTest;
import com.google.gerrit.acceptance.GerritConfig;
import com.google.gerrit.acceptance.PushOneCommit;
import com.google.gerrit.acceptance.RestResponse;
import com.google.gerrit.acceptance.Sandboxed;
import com.google.gerrit.common.data.GlobalCapability;
import com.google.gerrit.common.data.Permission;
import com.google.gerrit.extensions.common.AccountExternalIdInfo;
import com.google.gerrit.metrics.MetricMaker;
import com.google.gerrit.reviewdb.client.Account;
import com.google.gerrit.reviewdb.client.RefNames;
import com.google.gerrit.server.account.externalids.DisabledExternalIdCache;
import com.google.gerrit.server.account.externalids.ExternalId;
import com.google.gerrit.server.account.externalids.ExternalIdReader;
import com.google.gerrit.server.account.externalids.ExternalIds;
import com.google.gerrit.server.account.externalids.ExternalIdsUpdate;
import com.google.gerrit.server.account.externalids.ExternalIdsUpdate.RefsMetaExternalIdsUpdate;
import com.google.gerrit.server.config.AllUsersName;
import com.google.gerrit.server.git.LockFailureException;
import com.google.gson.reflect.TypeToken;
import com.google.gwtorm.server.OrmException;
import com.google.inject.Inject;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import org.eclipse.jgit.api.errors.TransportException;
import org.eclipse.jgit.errors.ConfigInvalidException;
import org.eclipse.jgit.internal.storage.dfs.InMemoryRepository;
import org.eclipse.jgit.junit.TestRepository;
import org.eclipse.jgit.lib.ObjectId;
import org.eclipse.jgit.lib.ObjectInserter;
import org.eclipse.jgit.lib.Repository;
import org.eclipse.jgit.notes.NoteMap;
import org.eclipse.jgit.revwalk.RevWalk;
import org.junit.Test;

@Sandboxed
public class ExternalIdIT extends AbstractDaemonTest {
  @Inject private AllUsersName allUsers;
  @Inject private ExternalIdsUpdate.Server extIdsUpdate;
  @Inject private ExternalIds externalIds;
  @Inject private ExternalIdReader externalIdReader;
  @Inject private MetricMaker metricMaker;

  @Test
  public void getExternalIds() throws Exception {
    Collection<ExternalId> expectedIds = accountCache.get(user.getId()).getExternalIds();

    List<AccountExternalIdInfo> expectedIdInfos = new ArrayList<>();
    for (ExternalId id : expectedIds) {
      AccountExternalIdInfo info = new AccountExternalIdInfo();
      info.identity = id.key().get();
      info.emailAddress = id.email();
      info.canDelete = !id.isScheme(SCHEME_USERNAME) ? true : null;
      info.trusted = true;
      expectedIdInfos.add(info);
    }

    RestResponse response = userRestSession.get("/accounts/self/external.ids");
    response.assertOK();

    List<AccountExternalIdInfo> results =
        newGson()
            .fromJson(
                response.getReader(), new TypeToken<List<AccountExternalIdInfo>>() {}.getType());

    Collections.sort(expectedIdInfos);
    Collections.sort(results);
    assertThat(results).containsExactlyElementsIn(expectedIdInfos);
  }

  @Test
  public void deleteExternalIds() throws Exception {
    setApiUser(user);
    List<AccountExternalIdInfo> externalIds = gApi.accounts().self().getExternalIds();

    List<String> toDelete = new ArrayList<>();
    List<AccountExternalIdInfo> expectedIds = new ArrayList<>();
    for (AccountExternalIdInfo id : externalIds) {
      if (id.canDelete != null && id.canDelete) {
        toDelete.add(id.identity);
        continue;
      }
      expectedIds.add(id);
    }

    assertThat(toDelete).hasSize(1);

    RestResponse response = userRestSession.post("/accounts/self/external.ids:delete", toDelete);
    response.assertNoContent();
    List<AccountExternalIdInfo> results = gApi.accounts().self().getExternalIds();
    // The external ID in WebSession will not be set for tests, resulting that
    // "mailto:user@example.com" can be deleted while "username:user" can't.
    assertThat(results).hasSize(1);
    assertThat(results).containsExactlyElementsIn(expectedIds);
  }

  @Test
  public void deleteExternalIdOfPreferredEmail() throws Exception {
    String preferredEmail = gApi.accounts().self().get().email;
    assertThat(preferredEmail).isNotNull();

    gApi.accounts()
        .self()
        .deleteExternalIds(
            ImmutableList.of(ExternalId.Key.create(SCHEME_MAILTO, preferredEmail).get()));
    assertThat(gApi.accounts().self().get().email).isNull();
  }

  @Test
  public void deleteExternalIds_Conflict() throws Exception {
    List<String> toDelete = new ArrayList<>();
    String externalIdStr = "username:" + user.username;
    toDelete.add(externalIdStr);
    RestResponse response = userRestSession.post("/accounts/self/external.ids:delete", toDelete);
    response.assertConflict();
    assertThat(response.getEntityContent())
        .isEqualTo(String.format("External id %s cannot be deleted", externalIdStr));
  }

  @Test
  public void deleteExternalIds_UnprocessableEntity() throws Exception {
    List<String> toDelete = new ArrayList<>();
    String externalIdStr = "mailto:user@domain.com";
    toDelete.add(externalIdStr);
    RestResponse response = userRestSession.post("/accounts/self/external.ids:delete", toDelete);
    response.assertUnprocessableEntity();
    assertThat(response.getEntityContent())
        .isEqualTo(String.format("External id %s does not exist", externalIdStr));
  }

  @Test
  public void fetchExternalIdsBranch() throws Exception {
    TestRepository<InMemoryRepository> allUsersRepo = cloneProject(allUsers, user);

    // refs/meta/external-ids is only visible to users with the 'Access Database' capability
    try {
      fetch(allUsersRepo, RefNames.REFS_EXTERNAL_IDS);
      fail("expected TransportException");
    } catch (TransportException e) {
      assertThat(e.getMessage())
          .isEqualTo(
              "Remote does not have " + RefNames.REFS_EXTERNAL_IDS + " available for fetch.");
    }

    allowGlobalCapabilities(REGISTERED_USERS, GlobalCapability.ACCESS_DATABASE);

    // re-clone to get new request context, otherwise the old global capabilities are still cached
    // in the IdentifiedUser object
    allUsersRepo = cloneProject(allUsers, user);
    fetch(allUsersRepo, RefNames.REFS_EXTERNAL_IDS);
  }

  @Test
  public void pushToExternalIdsBranch() throws Exception {
    grant(Permission.READ, allUsers, RefNames.REFS_EXTERNAL_IDS);
    grant(Permission.PUSH, allUsers, RefNames.REFS_EXTERNAL_IDS);

    TestRepository<InMemoryRepository> allUsersRepo = cloneProject(allUsers);
    fetch(allUsersRepo, RefNames.REFS_EXTERNAL_IDS + ":externalIds");
    allUsersRepo.reset("externalIds");
    PushOneCommit push = pushFactory.create(db, admin.getIdent(), allUsersRepo);
    push.to(RefNames.REFS_EXTERNAL_IDS)
        .assertErrorStatus("not allowed to update " + RefNames.REFS_EXTERNAL_IDS);
  }

  @Test
  public void retryOnLockFailure() throws Exception {
    Retryer<RefsMetaExternalIdsUpdate> retryer =
        ExternalIdsUpdate.retryerBuilder()
            .withBlockStrategy(
                new BlockStrategy() {
                  @Override
                  public void block(long sleepTime) {
                    // Don't sleep in tests.
                  }
                })
            .build();

    ExternalId.Key fooId = ExternalId.Key.create("foo", "foo");
    ExternalId.Key barId = ExternalId.Key.create("bar", "bar");

    final AtomicBoolean doneBgUpdate = new AtomicBoolean(false);
    ExternalIdsUpdate update =
        new ExternalIdsUpdate(
            repoManager,
            allUsers,
            metricMaker,
            externalIds,
            new DisabledExternalIdCache(),
            serverIdent.get(),
            serverIdent.get(),
            () -> {
              if (!doneBgUpdate.getAndSet(true)) {
                try {
                  extIdsUpdate.create().insert(db, ExternalId.create(barId, admin.id));
                } catch (IOException | ConfigInvalidException | OrmException e) {
                  // Ignore, the successful insertion of the external ID is asserted later
                }
              }
            },
            retryer);
    assertThat(doneBgUpdate.get()).isFalse();
    update.insert(db, ExternalId.create(fooId, admin.id));
    assertThat(doneBgUpdate.get()).isTrue();

    assertThat(externalIds.get(db, fooId)).isNotNull();
    assertThat(externalIds.get(db, barId)).isNotNull();
  }

  @Test
  public void failAfterRetryerGivesUp() throws Exception {
    ExternalId.Key[] extIdsKeys = {
      ExternalId.Key.create("foo", "foo"),
      ExternalId.Key.create("bar", "bar"),
      ExternalId.Key.create("baz", "baz")
    };
    final AtomicInteger bgCounter = new AtomicInteger(0);
    ExternalIdsUpdate update =
        new ExternalIdsUpdate(
            repoManager,
            allUsers,
            metricMaker,
            externalIds,
            new DisabledExternalIdCache(),
            serverIdent.get(),
            serverIdent.get(),
            () -> {
              try {
                extIdsUpdate
                    .create()
                    .insert(db, ExternalId.create(extIdsKeys[bgCounter.getAndAdd(1)], admin.id));
              } catch (IOException | ConfigInvalidException | OrmException e) {
                // Ignore, the successful insertion of the external ID is asserted later
              }
            },
            RetryerBuilder.<RefsMetaExternalIdsUpdate>newBuilder()
                .retryIfException(e -> e instanceof LockFailureException)
                .withStopStrategy(StopStrategies.stopAfterAttempt(extIdsKeys.length))
                .build());
    assertThat(bgCounter.get()).isEqualTo(0);
    try {
      update.insert(db, ExternalId.create(ExternalId.Key.create("abc", "abc"), admin.id));
      fail("expected LockFailureException");
    } catch (LockFailureException e) {
      // Ignore, expected
    }
    assertThat(bgCounter.get()).isEqualTo(extIdsKeys.length);
    for (ExternalId.Key extIdKey : extIdsKeys) {
      assertThat(externalIds.get(db, extIdKey)).isNotNull();
    }
  }

  @Test
  public void readExternalIdWithAccountIdThatCanBeExpressedInKiB() throws Exception {
    ExternalId.Key extIdKey = ExternalId.Key.parse("foo:bar");
    Account.Id accountId = new Account.Id(1024 * 100);
    extIdsUpdate.create().insert(db, ExternalId.create(extIdKey, accountId));
    ExternalId extId = externalIds.get(db, extIdKey);
    assertThat(extId.accountId()).isEqualTo(accountId);
  }

  @Test
  @GerritConfig(name = "user.readExternalIdsFromGit", value = "true")
  public void checkNoReloadAfterUpdate() throws Exception {
    Set<ExternalId> expectedExtIds = new HashSet<>(externalIds.byAccount(db, admin.id));
    externalIdReader.setFailOnLoad(true);

    // insert external ID
    ExternalId extId = ExternalId.create("foo", "bar", admin.id);
    extIdsUpdate.create().insert(db, extId);
    expectedExtIds.add(extId);
    assertThat(externalIds.byAccount(db, admin.id)).containsExactlyElementsIn(expectedExtIds);

    // update external ID
    expectedExtIds.remove(extId);
    extId = ExternalId.createWithEmail("foo", "bar", admin.id, "foo.bar@example.com");
    extIdsUpdate.create().upsert(db, extId);
    expectedExtIds.add(extId);
    assertThat(externalIds.byAccount(db, admin.id)).containsExactlyElementsIn(expectedExtIds);

    // delete external ID
    extIdsUpdate.create().delete(db, extId);
    expectedExtIds.remove(extId);
    assertThat(externalIds.byAccount(db, admin.id)).containsExactlyElementsIn(expectedExtIds);
  }

  @Test
  @GerritConfig(name = "user.readExternalIdsFromGit", value = "true")
  public void byAccountFailIfReadingExternalIdsFails() throws Exception {
    externalIdReader.setFailOnLoad(true);

    // update external ID branch so that external IDs need to be reloaded
    insertExtIdBehindGerritsBack(ExternalId.create("foo", "bar", admin.id));

    exception.expect(IOException.class);
    externalIds.byAccount(db, admin.id);
  }

  @Test
  @GerritConfig(name = "user.readExternalIdsFromGit", value = "true")
  public void byEmailFailIfReadingExternalIdsFails() throws Exception {
    externalIdReader.setFailOnLoad(true);

    // update external ID branch so that external IDs need to be reloaded
    insertExtIdBehindGerritsBack(ExternalId.create("foo", "bar", admin.id));

    exception.expect(IOException.class);
    externalIds.byEmail(db, admin.email);
  }

  @Test
  @GerritConfig(name = "user.readExternalIdsFromGit", value = "true")
  public void byAccountUpdateExternalIdsBehindGerritsBack() throws Exception {
    Set<ExternalId> expectedExternalIds = new HashSet<>(externalIds.byAccount(db, admin.id));
    ExternalId newExtId = ExternalId.create("foo", "bar", admin.id);
    insertExtIdBehindGerritsBack(newExtId);
    expectedExternalIds.add(newExtId);
    assertThat(externalIds.byAccount(db, admin.id)).containsExactlyElementsIn(expectedExternalIds);
  }

  private void insertExtIdBehindGerritsBack(ExternalId extId) throws Exception {
    try (Repository repo = repoManager.openRepository(allUsers);
        RevWalk rw = new RevWalk(repo);
        ObjectInserter ins = repo.newObjectInserter()) {
      ObjectId rev = ExternalIdReader.readRevision(repo);
      NoteMap noteMap = ExternalIdReader.readNoteMap(rw, rev);
      ExternalIdsUpdate.insert(rw, ins, noteMap, extId);
      ExternalIdsUpdate.commit(
          repo, rw, ins, rev, noteMap, "insert new ID", serverIdent.get(), serverIdent.get());
    }
  }
}
