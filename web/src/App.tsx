import { useEffect, useState } from "react";
import barberia from "@fixtures/reference-diagrams/barberia.json";
import veterinaria from "@fixtures/reference-diagrams/veterinaria.json";
import inventario from "@fixtures/reference-diagrams/inventario.json";
import edgeCases from "@fixtures/edge-cases.json";
import type { CanonicalModel } from "./types/canonical";
import type { Session } from "./components/LoginScreen";
import { useCanonicalStore } from "./store/canonicalStore";
import { DiagramWorkspace } from "./DiagramWorkspace";
import { DIAGRAMA_COLABORATIVO_ID } from "./services/config";

const DIAGRAMS: Record<string, CanonicalModel> = {
  barberia: barberia as CanonicalModel,
  veterinaria: veterinaria as CanonicalModel,
  inventario: inventario as CanonicalModel,
  "edge-cases": edgeCases as CanonicalModel,
};

// Unico diagrama que existe de verdad en el servidor (sembrado por
// scripts/seed-dev.sql con este mismo id); los otros tres son demos locales
// del Dia 6/7 sin colaboracion en vivo.
const DIAGRAMA_EN_VIVO: keyof typeof DIAGRAMS = "barberia";

interface Props {
  session: Session;
}

/**
 * Demo local del Dia 6/7: cuatro fixtures fijos, sin pasar por
 * proyectos/diagramas del servidor. Vive en su propia ruta (/demo) desde
 * que existen las pantallas reales -- se conserva porque sigue siendo la
 * evidencia mas directa de la notacion UML completa (rombos, triangulos,
 * punteado) sin depender de datos sembrados.
 */
function App({ session }: Props) {
  const [diagramKey, setDiagramKey] = useState<keyof typeof DIAGRAMS>("barberia");
  const setModel = useCanonicalStore((s) => s.setModel);

  useEffect(() => {
    setModel(DIAGRAMS[diagramKey]);
  }, [diagramKey, setModel]);

  const liveDiagramId = diagramKey === DIAGRAMA_EN_VIVO ? DIAGRAMA_COLABORATIVO_ID : null;

  return (
    <DiagramWorkspace
      session={session}
      diagramId={liveDiagramId}
      toolbarLeft={
        <>
          <label htmlFor="diagram-select">Diagrama:</label>
          <select
            id="diagram-select"
            value={diagramKey}
            onChange={(e) => setDiagramKey(e.target.value as keyof typeof DIAGRAMS)}
          >
            <option value="barberia">Barbería</option>
            <option value="veterinaria">Veterinaria</option>
            <option value="inventario">Inventario</option>
            <option value="edge-cases">Casos límite</option>
          </select>
        </>
      }
    />
  );
}

export default App;
