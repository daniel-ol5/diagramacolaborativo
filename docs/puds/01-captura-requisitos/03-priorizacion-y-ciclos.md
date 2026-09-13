# 2.2 Priorizar Casos de Uso

> Modelo de casos de uso — PUDS.
> Este documento define **el orden en que se construyen** los casos de uso y **cómo se
> reparten en los tres ciclos** del parcial. Es la sección que ordena todo el resto del
> documento: las secciones 2.3, 2.4, 2.5 y el capítulo 3 se organizan por ciclo.

---

## 2.2.1 Criterio de priorización

El Proceso Unificado es **dirigido por casos de uso e impulsado por el riesgo**. La
prioridad no la da la importancia comercial de un caso de uso, sino esta pregunta:

> **¿Qué pasa si construyo esto último y resulta que no se puede?**

Un caso de uso que, al fallar, obliga a rehacer todo lo demás va primero, aunque su valor
visible sea bajo. Uno rutinario va después, aunque el usuario lo use todos los días.

Se usaron cuatro criterios:

| Criterio | Qué mide |
| :--- | :--- |
| **Riesgo arquitectónico** | Si se resuelve mal, ¿cuánto hay que rehacer? |
| **Riesgo técnico externo** | ¿Depende de algo que puede no existir o no funcionar? |
| **Dependencia** | ¿Cuántos otros casos de uso lo necesitan antes? |
| **Valor observable** | ¿El usuario ve algo que sirve al terminar? |

---

## 2.2.2 Tabla de priorización

**Riesgo:** 🔴 crítico · 🟠 alto · 🟡 medio · 🟢 bajo

| # | Caso de uso | Riesgo | Por qué ese riesgo | Ciclo |
| :--- | :--- | :---: | :--- | :---: |
| CU13 | Editar el diagrama en tiempo real | 🔴 | Obliga a congelar el modelo canónico, el protocolo de mensajes, la persistencia y el orden de las operaciones. **Todo lo demás se construye encima**: el lienzo, el generador, el exportador y la app móvil comparten ese mismo modelo | **1** |
| CU14 | Bloquear elemento para edición exclusiva | 🔴 | Exclusión mutua distribuida. Resuelto con cola de espera introduce interbloqueo e inanición; hay que decidir la política antes de tener clientes que dependan de ella | **1** |
| CU27 | Generar app móvil con asistente de voz | 🔴 | Riesgo **externo**: dependía de si un teléfono transcribe voz sin internet. Si la respuesta era no, cambiaba el cronograma y el alcance | *spike* |
| CU24 | Sincronizar y reconciliar al reconectar | 🟠 | Define las reglas de resolución de conflictos. Tocarlas después obliga a revisar el cliente, el servidor y el registro histórico a la vez | **3** |
| CU26 | Generar backend Spring Boot | 🟠 | Sostiene la afirmación "el código generado siempre compila", que es la promesa central del proyecto | **3** |
| CU15 | Liberar bloqueo vencido | 🟠 | Sin él, un usuario que cierra el navegador deja elementos bloqueados para siempre | **1** |
| CU17 | Modelar clases | 🟡 | Ejercita el modelo canónico de punta a punta | **1** |
| CU18 | Modelar atributos | 🟡 | Los tipos de atributo determinan el código generado | **1** |
| CU20 | Modelar relaciones | 🟡 | El lado propietario de la relación decide el esquema de base de datos generado | **1** |
| CU16 | Mostrar presencia y cursores | 🟡 | Valida que exista un canal efímero que no ensucie el historial | **1** |
| CU07 | Abrir diagrama | 🟢 | Lectura simple, pero todo lo demás lo necesita | **1** |
| CU01 | Registrar usuario | 🟢 | Rutinario | **1** |
| CU02 | Iniciar sesión | 🟢 | Rutinario, pero habilita los roles de CU13 y CU14 | **1** |
| CU08 | Compartir proyecto por enlace | 🟠 | Un enlace mal diseñado no se puede revocar: si se filtra, el proyecto queda abierto para siempre | **2** |
| CU10 | Aceptar invitación | 🟡 | Aceptar no puede **bajar** un rol existente, o el propietario se degrada probando su propio enlace | **2** |
| CU03 | Crear proyecto | 🟢 | Rutinario | **2** |
| CU04 | Consultar mis proyectos | 🟢 | Rutinario | **2** |
| CU05 | Crear diagrama | 🟢 | Rutinario | **2** |
| CU06 | Consultar diagramas del proyecto | 🟢 | Rutinario | **2** |
| CU09 | Consultar invitación | 🟢 | Único endpoint público fuera de acceso | **2** |
| CU11 | Agregar miembro por correo | 🟢 | Rutinario | **2** |
| CU12 | Revocar invitación | 🟡 | Sin revocación, CU08 es un riesgo de seguridad sin remedio | **2** |
| CU19 | Modelar métodos | 🟢 | Los métodos generan esqueletos en el código | **2** |
| CU21 | Organizar en paquetes | 🟢 | Afecta al exportador XMI | **2** |
| CU23 | Modelar sin conexión | 🟠 | Detectar mal la desconexión hace que el usuario pierda trabajo creyendo que se guardó | **3** |
| CU25 | Validar diagrama antes de generar | 🟡 | Precondición de CU26 | **3** |
| CU22 | Alternar vista UML / ER | 🟡 | Segunda notación sobre el mismo modelo | **3** |
| CU28–CU32 | XMI e IA generativa | 🟡 | Alto valor, ninguna dependencia hacia abajo | *fuera del parcial* |

---

## 2.2.3 La decisión que hay que saber defender

**El ciclo 1 no empieza por "crear un proyecto", que sería lo natural.** Empieza por la
edición colaborativa, usando un proyecto y un diagrama cargados directamente en la base de
datos.

Visto desde afuera parece al revés. Es lo correcto, y el motivo es el principio central
del Proceso Unificado:

> Las primeras iteraciones atacan **el riesgo más alto**, no el flujo más obvio.

Crear un proyecto es un formulario: si sale mal se arregla en una tarde. La edición
colaborativa con exclusión mutua obliga a decidir el modelo canónico, el protocolo, el
orden de las operaciones y la política de bloqueos — y **si eso se decide mal, hay que
rehacer el lienzo, el generador y el exportador**, porque los tres se apoyan en el mismo
modelo.

Construir primero los formularios y dejar la colaboración para el final habría significado
descubrir el problema difícil con todo lo demás ya escrito encima.

La contrapartida se asumió a conciencia: **al terminar el ciclo 1 el sistema todavía no
servía para un usuario real**, porque no había forma de crear un proyecto propio. Eso se
resolvió en el ciclo 2. Un ciclo del Proceso Unificado entrega un incremento **ejecutable
y verificable**, no necesariamente comercializable.

**Y el spike de voz (CU27) es el mismo principio llevado al extremo.** El caso de uso más
lejano del cronograma escondía el riesgo menos controlable: ¿un teléfono transcribe voz sin
internet? Se respondió **el primer día**, con la aplicación más pequeña posible capaz de
responderlo, en un teléfono real y en modo avión. Si la respuesta hubiera sido no, cambiaba
el alcance — y averiguarlo en el último ciclo habría sido fatal.

---

## 2.2.4 Los tres ciclos

### CICLO 1 — Núcleo colaborativo

> **Objetivo:** dos personas abren el mismo diagrama y modelan juntas, sin pisarse.
> **Riesgo que elimina:** el modelo canónico y la exclusión mutua distribuida.

| # | Caso de uso | Estado |
| :--- | :--- | :---: |
| CU01 | Registrarse | 🟢 |
| CU02 | Iniciar sesión | 🟢 |
| CU07 | Abrir diagrama | 🟢 |
| CU13 | Editar el diagrama en tiempo real | 🟢 |
| CU14 | Bloquear elemento para edición exclusiva | 🟢 |
| CU15 | Liberar bloqueo vencido | 🟢 |
| CU16 | Mostrar presencia y cursores | 🟢 |
| CU17 | Modelar clases | 🟢 |
| CU18 | Modelar atributos | 🟢 |
| CU20 | Modelar relaciones | 🟢 |

**Resultado verificable del ciclo:** dos navegadores independientes, con usuarios
distintos, sobre el mismo diagrama. Uno mueve una clase y aparece en el otro sin recargar y
sin parpadeo; uno la renombra y el otro ve el candado y no puede editarla; un lector recibe
el rechazo y **su sesión sobrevive**.

---

### CICLO 2 — Gestión de proyectos y colaboración entre personas

> **Objetivo:** que el sistema deje de depender de datos cargados a mano. Cada usuario crea
> sus proyectos e invita a quien quiera.
> **Riesgo que elimina:** el otorgamiento de roles, que **no estaba en la especificación**.

| # | Caso de uso | Estado |
| :--- | :--- | :---: |
| CU03 | Crear proyecto | 🟢 |
| CU04 | Consultar mis proyectos | 🟢 |
| CU05 | Crear diagrama | 🟢 |
| CU06 | Consultar diagramas del proyecto | 🟢 |
| CU08 | Compartir proyecto por enlace | 🟢 |
| CU09 | Consultar invitación | 🟢 |
| CU10 | Aceptar invitación | 🟢 |
| CU11 | Agregar miembro por correo | 🟢 |
| CU12 | Revocar invitación | 🟢 |
| CU19 | Modelar métodos | 🟡 |
| CU21 | Organizar en paquetes | 🟡 |

**Resultado verificable del ciclo:** desde cero, un usuario crea un proyecto, crea un
diagrama, genera un enlace de invitación, y **otro usuario distinto lo abre, entra y edita
en vivo** — sin un solo dato insertado a mano en la base.

> **El hallazgo de este ciclo.** El sistema tenía tres roles definidos, arbitrados y
> probados… y **ninguna forma de otorgarlos**. La única manera de que dos personas
> colaboraran era escribir una fila en la base de datos. Apareció justamente al recorrer el
> catálogo completo de casos de uso, que es para lo que sirve esta etapa del proceso.

---

### CICLO 3 — Generación de código y trabajo sin conexión

> **Objetivo:** cerrar la promesa del proyecto — de un diagrama sale código que compila — y
> que la herramienta funcione sin internet.
> **Riesgo que elimina:** la invariante "100% compila" y la resolución de conflictos.

| # | Caso de uso | Estado |
| :--- | :--- | :---: |
| CU22 | Alternar vista UML / Entidad-Relación | ⬜ |
| CU23 | Modelar sin conexión | ⬜ |
| CU24 | Sincronizar y reconciliar al reconectar | 🟡 servidor listo |
| CU25 | Validar diagrama antes de generar | ⬜ |
| CU26 | Generar backend Spring Boot | 🟡 plantillas listas |

**Resultado verificable del ciclo:** cortar la red, hacer tres cambios, reconectar y
obtener el informe de reconciliación con lo aplicado y lo descartado; y descargar el
proyecto generado de un diagrama y compilarlo.

---

### Fuera del parcial — ciclos 4 y 5

| # | Caso de uso | Ciclo previsto |
| :--- | :--- | :---: |
| CU30 | Modelar por comando de texto | 4 |
| CU31 | Modelar por comando de voz | 4 |
| CU32 | Importar diagrama desde foto | 4 |
| CU28 | Exportar a XMI 2.1 | 4 |
| CU29 | Importar desde XMI 2.1 | 4 |
| CU27 | Generar app móvil con asistente de voz | 5 |

Se dejan fuera **por prioridad, no por dificultad**. Ninguno de ellos es precondición de
otro caso de uso: son hojas del árbol de dependencias. Eso los convierte en lo primero que
se recorta si hay atraso, y en lo último que se construye si no lo hay.

---

## 2.2.5 Resumen

| Ciclo | Casos de uso | Completos | Parciales | Sin empezar |
| :---: | :---: | :---: | :---: | :---: |
| **1** | 10 | 10 | 0 | 0 |
| **2** | 11 | 9 | 2 | 0 |
| **3** | 5 | 0 | 2 | 3 |
| Fuera | 6 | 0 | 0 | 6 |
| **Total** | **32** | **19** | **4** | **9** |

**Los ciclos 1 y 2 están cerrados y verificados. El ciclo 3 está en curso.**
