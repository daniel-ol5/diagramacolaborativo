import 'dart:io';

import 'package:flutter/material.dart';
import 'package:image_picker/image_picker.dart';

import '../services/config_service.dart';
import '../services/vision_capture_service.dart';

enum _Estado { esperandoFoto, subiendo, resultado, error }

/// Sacar y subir, nada mas. El telefono NO revisa ni corrige la
/// propuesta -- eso pasa en la computadora (CU17 en la web). Esta
/// pantalla solo muestra lo que diga el servidor.
class PhotoCaptureScreen extends StatefulWidget {
  const PhotoCaptureScreen({super.key, required this.sesion, required this.diagramaId});

  final Sesion sesion;
  final String diagramaId;

  @override
  State<PhotoCaptureScreen> createState() => _PhotoCaptureScreenState();
}

class _PhotoCaptureScreenState extends State<PhotoCaptureScreen> {
  _Estado _estado = _Estado.esperandoFoto;
  VisionCaptureResult? _resultado;
  String? _error;

  Future<void> _sacarYSubir() async {
    final XFile? foto = await ImagePicker().pickImage(source: ImageSource.camera, imageQuality: 85);
    if (foto == null || !mounted) return; // canceló

    setState(() => _estado = _Estado.subiendo);
    try {
      final resultado = await VisionCaptureService.subirFoto(
        direccionServidor: widget.sesion.direccionServidor,
        token: widget.sesion.token,
        diagramId: widget.diagramaId,
        foto: File(foto.path),
      );
      if (!mounted) return;
      setState(() {
        _resultado = resultado;
        _estado = _Estado.resultado;
      });
    } on VisionCaptureException catch (e) {
      if (!mounted) return;
      setState(() {
        _error = e.mensaje;
        _estado = _Estado.error;
      });
    }
  }

  @override
  Widget build(BuildContext context) {
    return Scaffold(
      appBar: AppBar(title: const Text('Sacar una foto')),
      body: Center(
        child: Padding(
          padding: const EdgeInsets.all(24),
          child: _contenido(),
        ),
      ),
    );
  }

  Widget _contenido() {
    switch (_estado) {
      case _Estado.esperandoFoto:
        return Column(
          mainAxisSize: MainAxisSize.min,
          children: [
            const Icon(Icons.camera_alt, size: 64),
            const SizedBox(height: 16),
            const Text(
              'Sacá una foto de la pizarra o el papel. Se analiza en la computadora, no acá.',
              textAlign: TextAlign.center,
            ),
            const SizedBox(height: 24),
            FilledButton(onPressed: _sacarYSubir, child: const Text('Abrir cámara')),
          ],
        );

      case _Estado.subiendo:
        // El servidor puede tardar hasta 45s reintentando contra el
        // modelo real antes de contestar -- sin este aviso, medio minuto
        // de pantalla quieta parece colgado, no ocupado (encontrado en el
        // pase del 20/09).
        return const Column(
          mainAxisSize: MainAxisSize.min,
          children: [
            CircularProgressIndicator(),
            SizedBox(height: 16),
            Text('Subiendo y analizando la foto…'),
            SizedBox(height: 8),
            Text(
              'Puede tardar hasta un minuto.',
              style: TextStyle(fontSize: 13, color: Colors.black54),
              textAlign: TextAlign.center,
            ),
          ],
        );

      case _Estado.resultado:
        final r = _resultado!;
        return Column(
          mainAxisSize: MainAxisSize.min,
          children: [
            Icon(
              r.delivered ? Icons.check_circle : Icons.info_outline,
              size: 48,
              color: r.delivered ? Colors.green : null,
            ),
            const SizedBox(height: 12),
            // El mensaje se muestra TAL CUAL lo manda el servidor -- mismo
            // criterio que CU16/CU17 en la web, nunca reescrito.
            Text(r.message, textAlign: TextAlign.center),
            const SizedBox(height: 24),
            Row(
              mainAxisAlignment: MainAxisAlignment.center,
              children: [
                OutlinedButton(
                  onPressed: () => setState(() => _estado = _Estado.esperandoFoto),
                  child: const Text('Sacar otra'),
                ),
                const SizedBox(width: 12),
                FilledButton(onPressed: () => Navigator.of(context).pop(), child: const Text('Listo')),
              ],
            ),
          ],
        );

      case _Estado.error:
        return Column(
          mainAxisSize: MainAxisSize.min,
          children: [
            const Icon(Icons.error_outline, size: 48, color: Colors.red),
            const SizedBox(height: 12),
            Text(_error ?? 'Ocurrió un error.', textAlign: TextAlign.center),
            const SizedBox(height: 24),
            FilledButton(
              onPressed: () => setState(() => _estado = _Estado.esperandoFoto),
              child: const Text('Reintentar'),
            ),
          ],
        );
    }
  }
}
