-- ModelCollab :: datos de prueba para el Dia 2 (Auth + STOMP + Docker).
-- Idempotente: se puede correr varias veces sin duplicar filas ni romper nada.
-- UUIDs fijos a mano (no gen_random_uuid()) para que los scripts de humo
-- (scripts/stomp-smoke.mjs) puedan citarlos por id.
--
-- Ejecutar con (Windows, psql no esta en el PATH):
--   "C:\Program Files\PostgreSQL\18\bin\psql.exe" -U postgres -d modelcollab -f scripts/seed-dev.sql
--
-- Los tres usuarios comparten la contrasena Modelcollab1! El hash de abajo es
-- BCrypt (costo 10, generado con bcryptjs) y su round-trip (hash <-> password)
-- se verifico a mano con bcryptjs antes de pegarlo aca, antes de que un typo
-- se manifieste como un 401 confuso a mitad de una prueba.

-- 1. Usuarios -----------------------------------------------------------
INSERT INTO users (id, email, password_hash, full_name, created_at)
VALUES
    ('11111111-1111-1111-1111-111111111111', 'owner@modelcollab.dev',  '$2a$10$6mysJOcgL/FXBAWi40Nk9u774Wkqdv6wUFC5aJJG4VljRIEeHngo2', 'Dueno del Proyecto', CURRENT_TIMESTAMP),
    ('22222222-2222-2222-2222-222222222222', 'editor@modelcollab.dev', '$2a$10$6mysJOcgL/FXBAWi40Nk9u774Wkqdv6wUFC5aJJG4VljRIEeHngo2', 'Editora de Prueba',  CURRENT_TIMESTAMP),
    ('33333333-3333-3333-3333-333333333333', 'viewer@modelcollab.dev', '$2a$10$6mysJOcgL/FXBAWi40Nk9u774Wkqdv6wUFC5aJJG4VljRIEeHngo2', 'Visor de Prueba',    CURRENT_TIMESTAMP)
ON CONFLICT (id) DO NOTHING;

-- 2. Proyecto (propiedad del owner) -------------------------------------
INSERT INTO projects (id, name, description, owner_id, created_at)
VALUES (
    '44444444-4444-4444-4444-444444444444',
    'Barberia (seed dev)',
    'Proyecto de datos de prueba para Dia 2: auth JWT y STOMP con roles.',
    '11111111-1111-1111-1111-111111111111',
    CURRENT_TIMESTAMP
)
ON CONFLICT (id) DO NOTHING;

-- 3. Membresias -----------------------------------------------------------
INSERT INTO project_members (project_id, user_id, role)
VALUES
    ('44444444-4444-4444-4444-444444444444', '11111111-1111-1111-1111-111111111111', 'OWNER'),
    ('44444444-4444-4444-4444-444444444444', '22222222-2222-2222-2222-222222222222', 'EDITOR'),
    ('44444444-4444-4444-4444-444444444444', '33333333-3333-3333-3333-333333333333', 'VIEWER')
ON CONFLICT (project_id, user_id) DO NOTHING;

-- 4. Diagrama (current_state = fixture congelado del Dia 1, barberia.json) -
-- Clase Cliente = f4281e21-43e9-5959-97ad-7bcaaef35382 (util como targetId
-- de un MOVE_CLASS en scripts/stomp-smoke.mjs).
INSERT INTO diagrams (id, project_id, name, schema_version, mutation_version, current_state, updated_at)
VALUES (
    '55555555-5555-5555-5555-555555555555',
    '44444444-4444-4444-4444-444444444444',
    'Barberia',
    '1.0.0',
    1,
    $barberia${
  "schemaVersion": "1.0.0",
  "mutationVersion": 1,
  "packages": [
    {
      "id": "40bcd2a1-ef8d-50e7-bacf-1ce93f0fac95",
      "name": "barberia",
      "classIds": [
        "f4281e21-43e9-5959-97ad-7bcaaef35382",
        "900f9c73-4ca3-5453-89a6-63fb70b8b78e",
        "817ef8ca-6485-55c8-bed6-8e3c93be4726",
        "c3383fbd-5db2-5655-a32c-28a4a6c71706",
        "f8ecc8ec-5351-54ca-b969-93075c12e0ec"
      ]
    }
  ],
  "classes": [
    {
      "id": "f4281e21-43e9-5959-97ad-7bcaaef35382",
      "name": "Cliente",
      "visibility": "PUBLIC",
      "isAbstract": false,
      "position": {
        "x": 60.0,
        "y": 60.0
      },
      "width": 240.0,
      "height": 200.0,
      "attributes": [
        {
          "id": "379937c2-d2de-522a-afda-fd2df7719bd7",
          "name": "id",
          "type": "UUID",
          "length": 36,
          "precision": 10,
          "scale": 2,
          "visibility": "PRIVATE",
          "isPrimaryKey": true,
          "isNullable": false,
          "isUnique": true
        },
        {
          "id": "f18540ae-04db-5263-88d5-fa1acefa71c0",
          "name": "nombre",
          "type": "VARCHAR",
          "length": 100,
          "precision": 10,
          "scale": 2,
          "visibility": "PRIVATE",
          "isPrimaryKey": false,
          "isNullable": false,
          "isUnique": false
        },
        {
          "id": "ec657071-a2d0-5687-9c39-d5f845fd0038",
          "name": "telefono",
          "type": "VARCHAR",
          "length": 20,
          "precision": 10,
          "scale": 2,
          "visibility": "PRIVATE",
          "isPrimaryKey": false,
          "isNullable": false,
          "isUnique": true
        },
        {
          "id": "2d8b2cbe-5a8d-5510-a4fe-8a6acc6a6e18",
          "name": "email",
          "type": "VARCHAR",
          "length": 150,
          "precision": 10,
          "scale": 2,
          "visibility": "PRIVATE",
          "isPrimaryKey": false,
          "isNullable": true,
          "isUnique": false
        },
        {
          "id": "5b235e2a-58d0-5ffe-8665-76ee764c336a",
          "name": "fechaRegistro",
          "type": "DATETIME",
          "length": 255,
          "precision": 10,
          "scale": 2,
          "visibility": "PRIVATE",
          "isPrimaryKey": false,
          "isNullable": false,
          "isUnique": false
        }
      ],
      "methods": [
        {
          "id": "b9700d90-a414-5a3b-a11c-cda0dd1cd2cc",
          "name": "obtenerHistorialCitas",
          "returnType": "List<Cita>",
          "visibility": "PUBLIC",
          "parameters": []
        },
        {
          "id": "713f4bc8-e6d9-5abe-b732-e0ed6bea83a7",
          "name": "actualizarContacto",
          "returnType": "void",
          "visibility": "PUBLIC",
          "parameters": [
            {
              "name": "telefono",
              "type": "String"
            },
            {
              "name": "email",
              "type": "String"
            }
          ]
        }
      ]
    },
    {
      "id": "900f9c73-4ca3-5453-89a6-63fb70b8b78e",
      "name": "Barbero",
      "visibility": "PUBLIC",
      "isAbstract": false,
      "position": {
        "x": 400.0,
        "y": 60.0
      },
      "width": 240.0,
      "height": 200.0,
      "attributes": [
        {
          "id": "28aaf2d4-edef-5a23-9e3d-932ba535e51d",
          "name": "id",
          "type": "UUID",
          "length": 36,
          "precision": 10,
          "scale": 2,
          "visibility": "PRIVATE",
          "isPrimaryKey": true,
          "isNullable": false,
          "isUnique": true
        },
        {
          "id": "44dee5a6-cb5d-5884-9cbe-412c4e252103",
          "name": "nombre",
          "type": "VARCHAR",
          "length": 100,
          "precision": 10,
          "scale": 2,
          "visibility": "PRIVATE",
          "isPrimaryKey": false,
          "isNullable": false,
          "isUnique": false
        },
        {
          "id": "5ada1d3b-e5be-58d3-b387-671e024819ac",
          "name": "especialidad",
          "type": "VARCHAR",
          "length": 80,
          "precision": 10,
          "scale": 2,
          "visibility": "PRIVATE",
          "isPrimaryKey": false,
          "isNullable": true,
          "isUnique": false
        },
        {
          "id": "739e6acc-e4f3-5a39-ad4f-2628ba6de48e",
          "name": "activo",
          "type": "BOOLEAN",
          "length": 255,
          "precision": 10,
          "scale": 2,
          "visibility": "PRIVATE",
          "isPrimaryKey": false,
          "isNullable": false,
          "isUnique": false,
          "defaultValue": "true"
        },
        {
          "id": "b015e67a-304e-5b65-9337-84bf8db6cc56",
          "name": "comisionPorcentaje",
          "type": "DECIMAL",
          "length": 255,
          "precision": 5,
          "scale": 2,
          "visibility": "PRIVATE",
          "isPrimaryKey": false,
          "isNullable": false,
          "isUnique": false,
          "defaultValue": "10.00"
        }
      ],
      "methods": [
        {
          "id": "2b58982d-217a-5294-b6fd-3cb00afc2301",
          "name": "calcularComisionMensual",
          "returnType": "BigDecimal",
          "visibility": "PUBLIC",
          "parameters": [
            {
              "name": "mes",
              "type": "Integer"
            },
            {
              "name": "anio",
              "type": "Integer"
            }
          ]
        },
        {
          "id": "38cf3f36-9e9f-5f7d-9500-cb98d778dc07",
          "name": "estaDisponible",
          "returnType": "Boolean",
          "visibility": "PUBLIC",
          "parameters": [
            {
              "name": "fechaHora",
              "type": "LocalDateTime"
            }
          ]
        }
      ]
    },
    {
      "id": "817ef8ca-6485-55c8-bed6-8e3c93be4726",
      "name": "Servicio",
      "visibility": "PUBLIC",
      "isAbstract": false,
      "position": {
        "x": 740.0,
        "y": 60.0
      },
      "width": 240.0,
      "height": 180.0,
      "attributes": [
        {
          "id": "be2156ca-8f48-5c9b-8238-d8ef573b53e0",
          "name": "id",
          "type": "UUID",
          "length": 36,
          "precision": 10,
          "scale": 2,
          "visibility": "PRIVATE",
          "isPrimaryKey": true,
          "isNullable": false,
          "isUnique": true
        },
        {
          "id": "182dd403-99e9-579e-93f3-a8bac406da6b",
          "name": "nombre",
          "type": "VARCHAR",
          "length": 100,
          "precision": 10,
          "scale": 2,
          "visibility": "PRIVATE",
          "isPrimaryKey": false,
          "isNullable": false,
          "isUnique": true
        },
        {
          "id": "0c5cc007-e1d6-5bb3-a997-4add3a66fd71",
          "name": "descripcion",
          "type": "TEXT",
          "length": 255,
          "precision": 10,
          "scale": 2,
          "visibility": "PRIVATE",
          "isPrimaryKey": false,
          "isNullable": true,
          "isUnique": false
        },
        {
          "id": "e9948aeb-ed20-51e2-9b38-07f1490c92d2",
          "name": "precio",
          "type": "DECIMAL",
          "length": 255,
          "precision": 10,
          "scale": 2,
          "visibility": "PRIVATE",
          "isPrimaryKey": false,
          "isNullable": false,
          "isUnique": false
        },
        {
          "id": "a75616e0-7b62-5144-aede-f2632734a385",
          "name": "duracionMinutos",
          "type": "INTEGER",
          "length": 255,
          "precision": 10,
          "scale": 2,
          "visibility": "PRIVATE",
          "isPrimaryKey": false,
          "isNullable": false,
          "isUnique": false,
          "defaultValue": "30"
        }
      ],
      "methods": []
    },
    {
      "id": "c3383fbd-5db2-5655-a32c-28a4a6c71706",
      "name": "Cita",
      "visibility": "PUBLIC",
      "isAbstract": false,
      "position": {
        "x": 400.0,
        "y": 360.0
      },
      "width": 260.0,
      "height": 220.0,
      "attributes": [
        {
          "id": "4d22d108-8d70-598a-ad79-82934ec63031",
          "name": "id",
          "type": "UUID",
          "length": 36,
          "precision": 10,
          "scale": 2,
          "visibility": "PRIVATE",
          "isPrimaryKey": true,
          "isNullable": false,
          "isUnique": true
        },
        {
          "id": "894c003f-d578-5cfa-84e8-682f9f8bdf01",
          "name": "fechaHora",
          "type": "DATETIME",
          "length": 255,
          "precision": 10,
          "scale": 2,
          "visibility": "PRIVATE",
          "isPrimaryKey": false,
          "isNullable": false,
          "isUnique": false
        },
        {
          "id": "4f19c1c5-3db9-51e2-a55e-1ae832f5632e",
          "name": "estado",
          "type": "VARCHAR",
          "length": 20,
          "precision": 10,
          "scale": 2,
          "visibility": "PRIVATE",
          "isPrimaryKey": false,
          "isNullable": false,
          "isUnique": false,
          "defaultValue": "PENDIENTE"
        },
        {
          "id": "84a0cc58-66b7-596e-b170-eed1d4b3f2ea",
          "name": "observaciones",
          "type": "TEXT",
          "length": 255,
          "precision": 10,
          "scale": 2,
          "visibility": "PRIVATE",
          "isPrimaryKey": false,
          "isNullable": true,
          "isUnique": false
        },
        {
          "id": "33871258-ffaa-5732-95fa-936781c0cff7",
          "name": "total",
          "type": "DECIMAL",
          "length": 255,
          "precision": 10,
          "scale": 2,
          "visibility": "PRIVATE",
          "isPrimaryKey": false,
          "isNullable": false,
          "isUnique": false,
          "defaultValue": "0.00"
        }
      ],
      "methods": [
        {
          "id": "e9db0296-39e9-5b69-98b5-8d7bcd546743",
          "name": "confirmar",
          "returnType": "void",
          "visibility": "PUBLIC",
          "parameters": []
        },
        {
          "id": "0238e9f3-0bc5-55d4-85f6-ef79e2182783",
          "name": "cancelar",
          "returnType": "void",
          "visibility": "PUBLIC",
          "parameters": [
            {
              "name": "motivo",
              "type": "String"
            }
          ]
        },
        {
          "id": "e37c78f3-05fc-5f93-bdf0-1526f61abbb9",
          "name": "calcularTotal",
          "returnType": "BigDecimal",
          "visibility": "PUBLIC",
          "parameters": []
        }
      ]
    },
    {
      "id": "f8ecc8ec-5351-54ca-b969-93075c12e0ec",
      "name": "Pago",
      "visibility": "PUBLIC",
      "isAbstract": false,
      "position": {
        "x": 740.0,
        "y": 640.0
      },
      "width": 240.0,
      "height": 200.0,
      "attributes": [
        {
          "id": "369f4401-f2a8-5d95-ac8f-99eb2cf7676d",
          "name": "id",
          "type": "UUID",
          "length": 36,
          "precision": 10,
          "scale": 2,
          "visibility": "PRIVATE",
          "isPrimaryKey": true,
          "isNullable": false,
          "isUnique": true
        },
        {
          "id": "f74573d0-b9ef-5b5e-bdd6-45ffcf273138",
          "name": "monto",
          "type": "DECIMAL",
          "length": 255,
          "precision": 10,
          "scale": 2,
          "visibility": "PRIVATE",
          "isPrimaryKey": false,
          "isNullable": false,
          "isUnique": false
        },
        {
          "id": "f3fc95a9-e747-504e-9717-6c7d6ad08a42",
          "name": "metodo",
          "type": "VARCHAR",
          "length": 20,
          "precision": 10,
          "scale": 2,
          "visibility": "PRIVATE",
          "isPrimaryKey": false,
          "isNullable": false,
          "isUnique": false,
          "defaultValue": "EFECTIVO"
        },
        {
          "id": "7bf7a252-3a2a-5dc8-be24-78a0deda9b5b",
          "name": "fechaPago",
          "type": "DATETIME",
          "length": 255,
          "precision": 10,
          "scale": 2,
          "visibility": "PRIVATE",
          "isPrimaryKey": false,
          "isNullable": false,
          "isUnique": false
        },
        {
          "id": "6b11ffb8-0611-5b9c-b06c-60ba83dd174d",
          "name": "referencia",
          "type": "VARCHAR",
          "length": 100,
          "precision": 10,
          "scale": 2,
          "visibility": "PRIVATE",
          "isPrimaryKey": false,
          "isNullable": true,
          "isUnique": false
        }
      ],
      "methods": [
        {
          "id": "169556bf-5123-591a-8950-35ce68459342",
          "name": "anular",
          "returnType": "void",
          "visibility": "PUBLIC",
          "parameters": [
            {
              "name": "motivo",
              "type": "String"
            }
          ]
        }
      ]
    }
  ],
  "relationships": [
    {
      "id": "8857f10a-9af5-526b-ae1c-957379b7894e",
      "sourceClassId": "f4281e21-43e9-5959-97ad-7bcaaef35382",
      "targetClassId": "c3383fbd-5db2-5655-a32c-28a4a6c71706",
      "type": "ASSOCIATION",
      "owningSide": "TARGET",
      "sourceMultiplicity": "1..1",
      "targetMultiplicity": "0..*",
      "sourceRole": "cliente",
      "targetRole": "citas",
      "isNavigable": true,
      "waypoints": [
        {
          "x": 300.0,
          "y": 260.0
        },
        {
          "x": 360.0,
          "y": 420.0
        }
      ]
    },
    {
      "id": "1a719b4c-0bf8-5831-83c7-43270c55ab7d",
      "sourceClassId": "900f9c73-4ca3-5453-89a6-63fb70b8b78e",
      "targetClassId": "c3383fbd-5db2-5655-a32c-28a4a6c71706",
      "type": "ASSOCIATION",
      "owningSide": "TARGET",
      "sourceMultiplicity": "1..1",
      "targetMultiplicity": "0..*",
      "sourceRole": "barbero",
      "targetRole": "citas",
      "isNavigable": true,
      "waypoints": []
    },
    {
      "id": "8ca8e3aa-01bb-56b0-9f47-81b45ad94c7c",
      "sourceClassId": "c3383fbd-5db2-5655-a32c-28a4a6c71706",
      "targetClassId": "817ef8ca-6485-55c8-bed6-8e3c93be4726",
      "type": "MANY_TO_MANY",
      "owningSide": "SOURCE",
      "joinTableName": "cita_servicio",
      "sourceMultiplicity": "0..*",
      "targetMultiplicity": "0..*",
      "sourceRole": "citas",
      "targetRole": "servicios",
      "isNavigable": true,
      "waypoints": [
        {
          "x": 700.0,
          "y": 400.0
        },
        {
          "x": 860.0,
          "y": 300.0
        }
      ]
    },
    {
      "id": "38b1960d-144f-5864-aa3e-82aa38c74d65",
      "sourceClassId": "c3383fbd-5db2-5655-a32c-28a4a6c71706",
      "targetClassId": "f8ecc8ec-5351-54ca-b969-93075c12e0ec",
      "type": "COMPOSITION",
      "owningSide": "TARGET",
      "sourceMultiplicity": "1..1",
      "targetMultiplicity": "0..1",
      "sourceRole": "cita",
      "targetRole": "pago",
      "isNavigable": true,
      "waypoints": []
    }
  ]
}$barberia$::jsonb,
    CURRENT_TIMESTAMP
)
ON CONFLICT (id) DO NOTHING;
