-- Enlaces de invitacion a un proyecto.
--
-- Es una TABLA y no un JWT firmado, y la diferencia es de seguridad, no de comodidad.
--
-- La tentacion es firmar {projectId, role} y no guardar nada: cero esquema, cero consultas. El
-- problema es que un enlace asi NO SE PUEDE REVOCAR. Y los enlaces para compartir se filtran —se
-- pegan en chats, quedan en historiales de navegacion, se reenvian sin pensar—, con lo cual el
-- proyecto quedaria abierto para siempre y sin nada que hacer al respecto. A la pregunta "¿y si
-- alguien filtra el enlace?" hay que poder responder algo mejor que "se pierde el proyecto".
-- Una fila se revoca; una firma valida no.
--
-- El token es aleatorio criptografico (SecureRandom, 32 bytes en base64url), nunca secuencial ni
-- derivado del id del proyecto: un token adivinable es lo mismo que no tener token.
CREATE TABLE project_invitations (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    project_id UUID NOT NULL REFERENCES projects(id) ON DELETE CASCADE,
    token VARCHAR(64) NOT NULL UNIQUE,
    role VARCHAR(20) NOT NULL CHECK (role IN ('OWNER', 'EDITOR', 'VIEWER')),
    created_by UUID REFERENCES users(id) ON DELETE SET NULL,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP,
    -- Nulos a proposito: un enlace sin vencimiento y uno sin revocar son el caso normal.
    expires_at TIMESTAMP WITH TIME ZONE,
    revoked_at TIMESTAMP WITH TIME ZONE
);

-- La busqueda por token es la del camino caliente: ocurre cada vez que alguien abre un enlace.
-- El UNIQUE ya crea el indice, asi que solo hace falta el de listar por proyecto.
CREATE INDEX idx_project_invitations_project ON project_invitations(project_id);
