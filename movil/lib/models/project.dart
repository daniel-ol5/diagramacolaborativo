/// Espejo de ProjectResponse.java / web/src/access/types.ts (Project) -- el
/// mismo campo `role` (rol de QUIEN PREGUNTA, no un atributo del proyecto).
class Project {
  const Project({
    required this.id,
    required this.name,
    required this.description,
    required this.role,
  });

  final String id;
  final String name;
  final String? description;
  final String role;

  factory Project.fromJson(Map<String, dynamic> json) => Project(
        id: json['id'] as String,
        name: json['name'] as String,
        description: json['description'] as String?,
        role: json['role'] as String,
      );
}
