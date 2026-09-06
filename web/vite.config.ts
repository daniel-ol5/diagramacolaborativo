import path from 'node:path'
import react from '@vitejs/plugin-react'
import { defineConfig } from 'vite'

// Los diagramas de referencia (barberia/veterinaria/inventario.json) viven
// en backend/, congelados desde el Dia 1 para todas las capas. No se
// duplican dentro de web/: una copia divergente es un bug que aparece tarde
// y cuesta caro encontrar. server.fs.allow habilita servirlos fuera de la
// raiz del proyecto, y el alias evita rutas relativas "../../.." en el codigo.
const fixturesDir = path.resolve(import.meta.dirname, '../backend/src/test/resources/reference-diagrams')

// https://vite.dev/config/
export default defineConfig({
  plugins: [react()],
  resolve: {
    alias: {
      '@fixtures': fixturesDir,
    },
  },
  server: {
    fs: {
      allow: [import.meta.dirname, fixturesDir],
    },
  },
})
