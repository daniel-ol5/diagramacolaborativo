-- Idempotencia de las mutaciones, reforzada en el motor.
--
-- §13 declara idx_diagram_ops_client_mut como indice de busqueda, no como restriccion. Eso
-- alcanzaba mientras la deteccion de duplicados fuera una comprobacion previa en Java sobre
-- mensajes que llegan de a uno por conexion STOMP. Con el rebase offline (§9.2) deja de
-- alcanzar: las operaciones llegan EN LOTE, y dos reintentos del mismo lote pueden solaparse.
-- Un chequeo leido-antes-de-escribir no es atomico; una restriccion UNIQUE si.
--
-- La restriccion es sobre el PAR (diagram_id, client_mutation_id) y no sobre la columna sola,
-- que es lo que corresponde: el clientMutationId lo genera el cliente para identificar SU
-- operacion dentro de UN diagrama. Global obligaria a que fuera unico en todo el sistema, algo
-- que ningun cliente puede garantizar y que ademas rompia la busqueda de duplicados: al
-- devolver un unico resultado, dos diagramas con el mismo identificador la hacian estallar.
--
-- Los NULL quedan permitidos y repetidos: PostgreSQL trata cada NULL como distinto en un indice
-- unico, asi que las operaciones sin identificador de cliente (las que no vienen de un cliente)
-- no colisionan entre si. Es exactamente el comportamiento que se quiere.
CREATE UNIQUE INDEX idx_diagram_ops_client_mut_unico
    ON diagram_operations (diagram_id, client_mutation_id);

-- El elemento sobre el que actua cada operacion.
--
-- §13 define diagram_operations con operation_type y payload pero SIN el identificador del
-- objetivo, y sin el la bitacora no se puede reproducir: el payload de un RENAME_CLASS es
-- {"name": "..."} y no dice que clase se renombro. Eso deja el log como auditoria parcial y
-- no como historial, cuando lo que necesita el rebase de §9.2 —y el "dame todo lo que paso
-- desde la secuencia N" del cliente que reconecta— es poder reconstruir.
--
-- El uso inmediato es el reporte de reconciliacion. §9.2 exige frases legibles del tipo
-- "la clase 'Cliente' fue eliminada por otro colaborador", y para cuando se arma ese texto la
-- clase ya no existe en el estado actual: el unico lugar donde queda su nombre es la bitacora.
--
-- Nullable porque las operaciones contra la raiz del diagrama (ADD_CLASS, BULK_MERGE) no tienen
-- objetivo, y porque las filas ya escritas no lo tienen.
ALTER TABLE diagram_operations ADD COLUMN target_id VARCHAR(64);
CREATE INDEX idx_diagram_ops_target ON diagram_operations(diagram_id, target_id);
