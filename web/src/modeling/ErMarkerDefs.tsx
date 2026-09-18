/**
 * Marcadores de pata de gallo (notacion Entidad-Relacion, CU15). Cuatro
 * simbolos, uno por cada valor de `Multiplicity` del canonico -- no hay
 * quinto caso que inventar, el propio tipo ya tiene exactamente estos
 * cuatro:
 *
 *   0..1  circulo + una barra
 *   1..1  dos barras (obligatorio, exactamente uno)
 *   0..*  circulo + pata de gallo
 *   1..*  una barra + pata de gallo
 *
 * Mismo criterio que UmlMarkerDefs: `auto-start-reverse` porque un mismo
 * marcador puede ir como markerStart o markerEnd segun que extremo de la
 * relacion se este dibujando, y con "auto" simple quedaria orientado al
 * reves en uno de los dos casos.
 */
const TINTA = "#1a1a2e"; // --color-border-strong / --color-text -- la misma tinta del lienzo, no un token nuevo (mismo criterio que UmlMarkerDefs).
const FONDO_LIENZO = "#fbfcfd"; // --canvas-bg

export function ErMarkerDefs() {
  return (
    <svg width={0} height={0} style={{ position: "absolute" }} aria-hidden="true">
      <defs>
        <marker id="er-mult-0-1" viewBox="0 0 30 16" markerWidth={30} markerHeight={16} markerUnits="userSpaceOnUse" refX={29} refY={8} orient="auto-start-reverse">
          <line x1={25} y1={1} x2={25} y2={15} stroke={TINTA} strokeWidth={1.5} />
          <circle cx={12} cy={8} r={6} fill={FONDO_LIENZO} stroke={TINTA} strokeWidth={1.5} />
        </marker>

        <marker id="er-mult-1-1" viewBox="0 0 20 16" markerWidth={20} markerHeight={16} markerUnits="userSpaceOnUse" refX={19} refY={8} orient="auto-start-reverse">
          <line x1={12} y1={1} x2={12} y2={15} stroke={TINTA} strokeWidth={1.5} />
          <line x1={17} y1={1} x2={17} y2={15} stroke={TINTA} strokeWidth={1.5} />
        </marker>

        <marker id="er-mult-0-many" viewBox="0 0 34 16" markerWidth={34} markerHeight={16} markerUnits="userSpaceOnUse" refX={33} refY={8} orient="auto-start-reverse">
          <path d="M14,8 L32,1 M14,8 L32,8 M14,8 L32,15" fill="none" stroke={TINTA} strokeWidth={1.5} />
          <circle cx={6} cy={8} r={6} fill={FONDO_LIENZO} stroke={TINTA} strokeWidth={1.5} />
        </marker>

        <marker id="er-mult-1-many" viewBox="0 0 30 16" markerWidth={30} markerHeight={16} markerUnits="userSpaceOnUse" refX={29} refY={8} orient="auto-start-reverse">
          <path d="M12,8 L28,1 M12,8 L28,8 M12,8 L28,15" fill="none" stroke={TINTA} strokeWidth={1.5} />
          <line x1={4} y1={1} x2={4} y2={15} stroke={TINTA} strokeWidth={1.5} />
        </marker>
      </defs>
    </svg>
  );
}
