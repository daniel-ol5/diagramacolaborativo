package com.modelcollab.assistant.settings;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.modelcollab.assistant.ai.AssistantLevel;
import com.modelcollab.assistant.ai.AssistantMode;
import com.modelcollab.assistant.ai.AssistantProvider;
import com.modelcollab.assistant.ai.AssistantSettings;
import com.modelcollab.assistant.chat.ChatParticipants;
import com.modelcollab.assistant.chat.ChatService;
import com.modelcollab.assistant.core.ChatAuthorType;
import com.modelcollab.project.model.ProjectRole;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

@ExtendWith(MockitoExtension.class)
class AssistantSettingsServiceTest {

    @Mock
    private AssistantSettingsRepository repository;

    @Mock
    private ChatParticipants participants;

    @Mock
    private ChatService chatService;

    private AssistantSettingsService service;

    private final UUID projectId = UUID.randomUUID();
    private final UUID userId = UUID.randomUUID();

    @BeforeEach
    void setUp() {
        service = new AssistantSettingsService(repository, participants, chatService);
    }

    @Test
    @DisplayName("getSettings: si el usuario no es miembro del proyecto se rechaza con 403")
    void getSettingsNoMiembro() {
        when(participants.roleIn(projectId, userId)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.getSettings(projectId, userId))
                .isInstanceOf(ResponseStatusException.class)
                .satisfies(e -> assertThat(((ResponseStatusException) e).getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN));
    }

    @Test
    @DisplayName("getSettings: si es OWNER y no hay ajustes guardados, devuelve DEFAULTS con canEdit=true")
    void getSettingsOwnerSinAjustesPrevios() {
        when(participants.roleIn(projectId, userId)).thenReturn(Optional.of(ProjectRole.OWNER));
        when(repository.findById(projectId)).thenReturn(Optional.empty());

        AssistantSettingsResponse res = service.getSettings(projectId, userId);

        assertThat(res.canEdit()).isTrue();
        assertThat(res.mode()).isEqualTo(AssistantMode.ON_MENTION);
        assertThat(res.level()).isEqualTo(AssistantLevel.INTERMEDIATE);
        assertThat(res.provider()).isEqualTo(AssistantProvider.CLOUD);
    }

    @Test
    @DisplayName("getSettings: si es EDITOR o VIEWER, devuelve canEdit=false")
    void getSettingsEditorSoloLectura() {
        when(participants.roleIn(projectId, userId)).thenReturn(Optional.of(ProjectRole.EDITOR));
        when(repository.findById(projectId)).thenReturn(Optional.empty());

        AssistantSettingsResponse res = service.getSettings(projectId, userId);

        assertThat(res.canEdit()).isFalse();
    }

    @Test
    @DisplayName("updateSettings: si no es OWNER se rechaza con 403")
    void updateSettingsNoOwner() {
        when(participants.roleIn(projectId, userId)).thenReturn(Optional.of(ProjectRole.EDITOR));

        AssistantSettingsUpdateRequest req = new AssistantSettingsUpdateRequest(
                AssistantMode.OFF, AssistantLevel.ADVANCED, AssistantProvider.CLOUD, null, 500, 20);

        assertThatThrownBy(() -> service.updateSettings(projectId, userId, req))
                .isInstanceOf(ResponseStatusException.class)
                .satisfies(e -> assertThat(((ResponseStatusException) e).getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN));
    }

    @Test
    @DisplayName("updateSettings: OWNER guarda correctamente y publica aviso del sistema en el chat")
    void updateSettingsOwnerGuardaYNotifica() {
        when(participants.roleIn(projectId, userId)).thenReturn(Optional.of(ProjectRole.OWNER));
        when(participants.fullName(userId)).thenReturn(Optional.of("Daniel"));
        when(repository.findById(projectId)).thenReturn(Optional.empty());

        AssistantSettingsUpdateRequest req = new AssistantSettingsUpdateRequest(
                AssistantMode.MENTOR_PROACTIVE,
                AssistantLevel.ADVANCED,
                AssistantProvider.CLOUD,
                null,
                500,
                25);

        AssistantSettingsResponse res = service.updateSettings(projectId, userId, req);

        assertThat(res.canEdit()).isTrue();
        assertThat(res.mode()).isEqualTo(AssistantMode.MENTOR_PROACTIVE);
        assertThat(res.level()).isEqualTo(AssistantLevel.ADVANCED);
        assertThat(res.provider()).isEqualTo(AssistantProvider.CLOUD);

        // Verifica que se guardó en el repositorio
        ArgumentCaptor<AssistantSettingsEntity> captor = ArgumentCaptor.forClass(AssistantSettingsEntity.class);
        verify(repository).save(captor.capture());
        assertThat(captor.getValue().getProjectId()).isEqualTo(projectId);
        assertThat(captor.getValue().getMode()).isEqualTo(AssistantMode.MENTOR_PROACTIVE);

        // Verifica que se publicó en el chat el aviso de sistema
        ArgumentCaptor<String> mensajeCaptor = ArgumentCaptor.forClass(String.class);
        verify(chatService).postFromAi(eq(projectId), eq(ChatAuthorType.SYSTEM), mensajeCaptor.capture(), any());
        assertThat(mensajeCaptor.getValue()).contains("Daniel actualizó la configuración de la IA");
        assertThat(mensajeCaptor.getValue()).contains("Mentor proactivo");
    }

    @Test
    @DisplayName("updateSettings: si elige proveedor LOCAL pero la URL es vacía o inválida se rechaza con 400")
    void updateSettingsLocalInvalido() {
        when(participants.roleIn(projectId, userId)).thenReturn(Optional.of(ProjectRole.OWNER));

        AssistantSettingsUpdateRequest reqSinUrl = new AssistantSettingsUpdateRequest(
                AssistantMode.ON_MENTION, AssistantLevel.INTERMEDIATE, AssistantProvider.LOCAL, "", 400, 30);

        assertThatThrownBy(() -> service.updateSettings(projectId, userId, reqSinUrl))
                .isInstanceOf(ResponseStatusException.class)
                .satisfies(e -> assertThat(((ResponseStatusException) e).getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST));

        AssistantSettingsUpdateRequest reqProtocoloInvalido = new AssistantSettingsUpdateRequest(
                AssistantMode.ON_MENTION, AssistantLevel.INTERMEDIATE, AssistantProvider.LOCAL, "ftp://localhost:11434", 400, 30);

        assertThatThrownBy(() -> service.updateSettings(projectId, userId, reqProtocoloInvalido))
                .isInstanceOf(ResponseStatusException.class)
                .satisfies(e -> assertThat(((ResponseStatusException) e).getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST));

        AssistantSettingsUpdateRequest reqHostPublico = new AssistantSettingsUpdateRequest(
                AssistantMode.ON_MENTION, AssistantLevel.INTERMEDIATE, AssistantProvider.LOCAL, "http://sitio-externo.com/v1", 400, 30);

        assertThatThrownBy(() -> service.updateSettings(projectId, userId, reqHostPublico))
                .isInstanceOf(ResponseStatusException.class)
                .satisfies(e -> assertThat(((ResponseStatusException) e).getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST));
    }

    @Test
    @DisplayName("updateSettings: permite proveedor LOCAL con localhost, host.docker.internal e IP privada")
    void updateSettingsLocalValido() {
        when(participants.roleIn(projectId, userId)).thenReturn(Optional.of(ProjectRole.OWNER));
        when(participants.fullName(userId)).thenReturn(Optional.of("Daniel"));
        when(repository.findById(projectId)).thenReturn(Optional.empty());

        AssistantSettingsUpdateRequest reqDocker = new AssistantSettingsUpdateRequest(
                AssistantMode.ON_MENTION, AssistantLevel.INTERMEDIATE, AssistantProvider.LOCAL, "http://host.docker.internal:11434/v1", 400, 30);

        AssistantSettingsResponse res = service.updateSettings(projectId, userId, reqDocker);
        assertThat(res.provider()).isEqualTo(AssistantProvider.LOCAL);
        assertThat(res.localEndpoint()).isEqualTo("http://host.docker.internal:11434/v1");
    }
}

