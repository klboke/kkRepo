package com.github.klboke.kkrepo.server.kkguard;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.github.klboke.kkrepo.persistence.jdbc.api.KkGuardDao;
import com.github.klboke.kkrepo.persistence.jdbc.api.KkGuardDao.Application;
import com.github.klboke.kkrepo.persistence.jdbc.api.KkGuardDao.ApplicationTag;
import com.github.klboke.kkrepo.persistence.jdbc.api.KkGuardDao.Organization;
import com.github.klboke.kkrepo.server.kkguard.KkGuardCatalogService.ApplicationInput;
import com.github.klboke.kkrepo.server.kkguard.KkGuardCatalogService.OrganizationInput;
import com.github.klboke.kkrepo.server.kkguard.KkGuardCatalogService.TagView;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

class KkGuardCatalogServiceTest {
  private static final String ORG_UUID = "11111111-1111-1111-1111-111111111111";
  private static final String APP_UUID = "22222222-2222-2222-2222-222222222222";
  private static final Instant NOW = Instant.parse("2026-01-01T00:00:00Z");

  private KkGuardDao dao;
  private KkGuardCatalogService service;

  @BeforeEach
  void setUp() {
    dao = mock(KkGuardDao.class);
    service = new KkGuardCatalogService(dao, new KkGuardProperties());
  }

  @Test
  void applicationsExposeIqFieldsWithStableTagIdentifiers() {
    when(dao.findApplicationByExternalId(APP_UUID)).thenReturn(Optional.of(application()));
    when(dao.findOrganization(7L)).thenReturn(Optional.of(organization()));
    when(dao.listApplicationTags(11L)).thenReturn(List.of(
        new ApplicationTag(11L, "payments", "payment services")));

    var view = service.application(APP_UUID);

    assertEquals(APP_UUID, view.id());
    assertEquals("orders-api", view.publicId());
    assertEquals(ORG_UUID, view.organizationId());
    assertEquals("jdoe", view.contactUserName());
    assertEquals(1, view.applicationTags().size());
    String expectedTagId = UUID.nameUUIDFromBytes(
        (APP_UUID + ":payments").getBytes(java.nio.charset.StandardCharsets.UTF_8)).toString();
    assertEquals(expectedTagId, view.applicationTags().get(0).id());
  }

  @Test
  void createApplicationValidatesNormalizesAndStoresTags() {
    when(dao.findOrganizationByExternalId(ORG_UUID)).thenReturn(Optional.of(organization()));
    when(dao.insertApplication(
        eq(7L), anyString(), eq("orders-api"), eq("Orders API"), eq(null), eq("jdoe"), any()))
        .thenReturn(11L);
    when(dao.findApplication(11L)).thenReturn(Optional.of(application()));
    when(dao.findOrganization(7L)).thenReturn(Optional.of(organization()));
    when(dao.listApplicationTags(11L)).thenReturn(List.of(
        new ApplicationTag(11L, "payments", null)));

    var view = service.createApplication(new ApplicationInput(
        " orders-api ", " Orders API ", ORG_UUID, " jdoe ",
        List.of(new TagView(null, "payments", null), new TagView(null, "billing", null))));

    assertEquals(APP_UUID, view.id());
    verify(dao).replaceApplicationTags(eq(11L), any());
  }

  @Test
  void duplicateTagNamesAreRejectedIgnoringCase() {
    when(dao.findOrganizationByExternalId(ORG_UUID)).thenReturn(Optional.of(organization()));

    ResponseStatusException error = assertThrows(ResponseStatusException.class,
        () -> service.createApplication(new ApplicationInput(
            "orders", "Orders", ORG_UUID, null,
            List.of(new TagView(null, "Payments", null), new TagView(null, "payments", null)))));

    assertEquals(HttpStatus.BAD_REQUEST, error.getStatusCode());
    assertTrue(error.getReason().contains("Duplicate"));
    verify(dao, never()).insertApplication(
        anyLong(), anyString(), anyString(), anyString(), any(), any(), any());
  }

  @Test
  void unknownOrganizationsAndApplicationsReturnNotFound() {
    when(dao.findOrganizationByExternalId(ORG_UUID)).thenReturn(Optional.empty());
    when(dao.findApplicationByExternalId(APP_UUID)).thenReturn(Optional.empty());

    assertEquals(HttpStatus.NOT_FOUND, assertThrows(ResponseStatusException.class,
        () -> service.organization(ORG_UUID)).getStatusCode());
    assertEquals(HttpStatus.NOT_FOUND, assertThrows(ResponseStatusException.class,
        () -> service.application(APP_UUID)).getStatusCode());
    assertEquals(HttpStatus.NOT_FOUND, assertThrows(ResponseStatusException.class,
        () -> service.createApplication(new ApplicationInput(
            "orders", "Orders", ORG_UUID, null, List.of()))).getStatusCode());
  }

  @Test
  void duplicatePublicIdsBecomeConflicts() {
    when(dao.findOrganizationByExternalId(ORG_UUID)).thenReturn(Optional.of(organization()));
    when(dao.insertApplication(anyLong(), anyString(), anyString(), anyString(), any(), any(),
        any())).thenThrow(new DuplicateKeyException("uk_kkg_application_public_id"));

    ResponseStatusException error = assertThrows(ResponseStatusException.class,
        () -> service.createApplication(new ApplicationInput(
            "orders", "Orders", ORG_UUID, null, List.of())));

    assertEquals(HttpStatus.CONFLICT, error.getStatusCode());
  }

  @Test
  void updatesKeepTheExternalIdentityAndReplaceTags() {
    when(dao.findApplicationByExternalId(APP_UUID)).thenReturn(Optional.of(application()));
    when(dao.findOrganizationByExternalId(ORG_UUID)).thenReturn(Optional.of(organization()));
    when(dao.findApplication(11L)).thenReturn(Optional.of(application()));
    when(dao.findOrganization(7L)).thenReturn(Optional.of(organization()));

    var updated = service.updateApplication(APP_UUID, new ApplicationInput(
        "orders-v2", "Orders v2", ORG_UUID, "asmith",
        List.of(new TagView(null, "critical", "tier 0"))));

    // The PUT response must carry the re-read DTO, not null.
    assertEquals(APP_UUID, updated.id());
    verify(dao).updateApplication(
        eq(11L), eq(7L), eq(APP_UUID), eq("orders-v2"), eq("Orders v2"), eq(5L), eq("asmith"),
        any());
    verify(dao).replaceApplicationTags(11L, List.of(
        new ApplicationTag(11L, "critical", "tier 0")));
  }

  @Test
  void organizationCreationRejectsBlankAndOversizedNames() {
    assertEquals(HttpStatus.BAD_REQUEST, assertThrows(ResponseStatusException.class,
        () -> service.createOrganization(new OrganizationInput("  ", null))).getStatusCode());
    assertEquals(HttpStatus.BAD_REQUEST, assertThrows(ResponseStatusException.class,
        () -> service.createOrganization(
            new OrganizationInput("x".repeat(KkGuardCatalogService.MAX_NAME + 1), null)))
        .getStatusCode());
  }

  @Test
  void deletingAnOrganizationCascadesThroughTheDatabase() {
    when(dao.findOrganizationByExternalId(ORG_UUID)).thenReturn(Optional.of(organization()));

    service.deleteOrganization(ORG_UUID);

    verify(dao).deleteOrganization(7L);
  }

  private static Organization organization() {
    return new Organization(7L, ORG_UUID, "Payments", "payment org", NOW, NOW);
  }

  private static Application application() {
    return new Application(
        11L, 7L, APP_UUID, "orders-api", "Orders API", 5L, "jdoe", null, null, NOW, NOW);
  }
}
