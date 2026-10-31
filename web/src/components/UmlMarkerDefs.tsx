/**
 * Marcadores SVG de notacion UML que React Flow no trae (rombos, triangulo
 * de herencia, flecha abierta). Se definen una sola vez y las aristas los
 * referencian por id via `url(#...)`; una referencia SVG por id funciona
 * entre cualquier <svg> del documento, no hace falta que vivan dentro del
 * mismo <svg> que dibuja el path.
 *
 * `markerUnits="userSpaceOnUse"` para que el tamano no dependa del
 * stroke-width de cada arista (si no, un stroke fino encoge el marcador
 * hasta volverlo casi invisible, que es lo primero que paso aca).
 *
 * El rombo (agregacion/composicion) puede ir tanto en `markerStart` como en
 * `markerEnd` segun `owningSide` (ver UmlRelationshipEdge) — el extremo
 * "todo" no es siempre el mismo lado. `orient="auto"` sola NO alcanza: para
 * `marker-start` "auto" orienta hacia AFUERA de la caja, mientras que para
 * `marker-end` orienta hacia ADENTRO, asi que un mismo `refX` deja el rombo
 * visible en un caso y tapado por el nodo (por debajo, invisible) en el
 * otro. `auto-start-reverse` iguala ambos casos a la misma convencion.
 * El triangulo de herencia y la flecha de dependencia solo se usan como
 * `markerEnd` (apuntan siempre al padre/al proveedor), por eso les alcanza
 * con `auto` simple.
 */
export function UmlMarkerDefs() {
  return (
    <svg width={0} height={0} style={{ position: "absolute" }} aria-hidden="true">
      <defs>
        <marker
          id="uml-diamond-hollow"
          viewBox="0 0 24 14"
          markerWidth={24}
          markerHeight={14}
          markerUnits="userSpaceOnUse"
          refX={23}
          refY={7}
          orient="auto-start-reverse"
        >
          <polygon points="0,7 12,0 24,7 12,14" fill="#fff" stroke="#273b40" strokeWidth={1.75} />
        </marker>

        <marker
          id="uml-diamond-filled"
          viewBox="0 0 24 14"
          markerWidth={24}
          markerHeight={14}
          markerUnits="userSpaceOnUse"
          refX={23}
          refY={7}
          orient="auto-start-reverse"
        >
          <polygon points="0,7 12,0 24,7 12,14" fill="#273b40" stroke="#273b40" strokeWidth={1.75} />
        </marker>

        <marker
          id="uml-triangle-hollow"
          viewBox="0 0 18 16"
          markerWidth={18}
          markerHeight={16}
          markerUnits="userSpaceOnUse"
          refX={17}
          refY={8}
          orient="auto"
        >
          <polygon points="0,0 18,8 0,16" fill="#fff" stroke="#273b40" strokeWidth={1.75} />
        </marker>

        <marker
          id="uml-arrow-open"
          viewBox="0 0 14 12"
          markerWidth={14}
          markerHeight={12}
          markerUnits="userSpaceOnUse"
          refX={13}
          refY={6}
          orient="auto"
        >
          <polyline points="0,0 13,6 0,12" fill="none" stroke="#273b40" strokeWidth={1.75} />
        </marker>
      </defs>
    </svg>
  );
}
