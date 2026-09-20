import 'package:flutter/material.dart';

import '../models/project.dart';
import '../services/api_service.dart';
import '../services/config_service.dart';
import 'diagram_list_screen.dart';
import 'error_view.dart';
import 'login_screen.dart';

class ProjectListScreen extends StatefulWidget {
  const ProjectListScreen({super.key, required this.sesion});

  final Sesion sesion;

  @override
  State<ProjectListScreen> createState() => _ProjectListScreenState();
}

class _ProjectListScreenState extends State<ProjectListScreen> {
  late final ApiService _api = ApiService(
    direccionServidor: widget.sesion.direccionServidor,
    token: widget.sesion.token,
  );
  Future<List<Project>>? _futuro;

  @override
  void initState() {
    super.initState();
    _cargar();
  }

  void _cargar() {
    setState(() {
      _futuro = _api.listarProyectos();
    });
  }

  Future<void> _cerrarSesion() async {
    await ConfigService.cerrarSesion();
    if (!mounted) return;
    Navigator.of(context).pushAndRemoveUntil(
      MaterialPageRoute(builder: (_) => const LoginScreen()),
      (route) => false,
    );
  }

  @override
  Widget build(BuildContext context) {
    return Scaffold(
      appBar: AppBar(
        title: Text(widget.sesion.nombreCompleto),
        actions: [
          IconButton(icon: const Icon(Icons.logout), tooltip: 'Cerrar sesión', onPressed: _cerrarSesion),
        ],
      ),
      body: FutureBuilder<List<Project>>(
        future: _futuro,
        builder: (context, snapshot) {
          if (snapshot.connectionState != ConnectionState.done) {
            return const Center(child: CircularProgressIndicator());
          }
          if (snapshot.hasError) {
            return ErrorView(mensaje: '${snapshot.error}', onReintentar: _cargar);
          }
          final proyectos = snapshot.data!;
          if (proyectos.isEmpty) {
            // La app no crea proyectos (ni cuentas): eso pasa en la web, la
            // misma cuenta. Sin decir DONDE, la pantalla vacia deja
            // buscando un boton que no existe (observado en el pase con
            // telefono real del 20/09).
            return const Center(
              child: Padding(
                padding: EdgeInsets.all(24),
                child: Text(
                  'Todavía no formás parte de ningún proyecto.\n\nLos proyectos se crean desde la web, con la misma cuenta.',
                  textAlign: TextAlign.center,
                ),
              ),
            );
          }
          return RefreshIndicator(
            onRefresh: () async => _cargar(),
            child: ListView.builder(
              itemCount: proyectos.length,
              itemBuilder: (context, i) {
                final p = proyectos[i];
                return ListTile(
                  title: Text(p.name),
                  subtitle: p.description != null ? Text(p.description!) : null,
                  trailing: Chip(
                    label: Text(p.role, style: const TextStyle(fontSize: 11)),
                    padding: EdgeInsets.zero,
                    visualDensity: VisualDensity.compact,
                    materialTapTargetSize: MaterialTapTargetSize.shrinkWrap,
                  ),
                  onTap: () {
                    Navigator.of(context).push(
                      MaterialPageRoute(
                        builder: (_) => DiagramListScreen(sesion: widget.sesion, proyecto: p),
                      ),
                    );
                  },
                );
              },
            ),
          );
        },
      ),
    );
  }
}
