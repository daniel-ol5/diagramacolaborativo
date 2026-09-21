import { useEffect, useState, type FormEvent } from "react";
import { Modal } from "../components/Modal";
import type { Session } from "../components/LoginScreen";
import {
  addMember,
  createInvitation,
  listInvitations,
  listMembers,
  revokeInvitation,
} from "./api";
import type { Invitation, Member, ProjectRole } from "./types";
import { fechaCorta, rolEnCastellano } from "./types";
import "./ShareModal.css";

interface Props {
  session: Session;
  projectId: string;
  onClose: () => void;
}

function estadoDe(inv: Invitation): { texto: string; inactivo: boolean } {
  if (inv.revokedAt) return { texto: "Revocado", inactivo: true };
  if (!inv.active) return { texto: "Vencido", inactivo: true };
  return { texto: "Activo", inactivo: false };
}

/**
 * CU10/CU11: enlace copiable con rol, invitaciones activas con revocar,
 * quien tiene acceso, agregar por correo. Todo esto es solo del dueño
 * (§ProjectService.exigirPropiedad) -- ProjectScreen ya no muestra el
 * boton "Compartir" si el rol no es OWNER, pero igual el servidor
 * responde 403 si alguien llega aca de otra forma.
 */
export function ShareModal({ session, projectId, onClose }: Props) {
  const [members, setMembers] = useState<Member[] | null>(null);
  const [invitations, setInvitations] = useState<Invitation[] | null>(null);
  const [error, setError] = useState<string | null>(null);

  const [newLinkRole, setNewLinkRole] = useState<ProjectRole>("EDITOR");
  const [newLinkExpires, setNewLinkExpires] = useState("");
  const [creatingLink, setCreatingLink] = useState(false);
  const [copiedId, setCopiedId] = useState<string | null>(null);

  const [memberEmail, setMemberEmail] = useState("");
  const [memberRole, setMemberRole] = useState<ProjectRole>("EDITOR");
  const [addingMember, setAddingMember] = useState(false);

  useEffect(() => {
    let cancelado = false;
    Promise.all([listMembers(session.token, projectId), listInvitations(session.token, projectId)])
      .then(([m, i]) => {
        if (cancelado) return;
        setMembers(m);
        setInvitations(i);
      })
      .catch((e) => {
        if (!cancelado) setError(e instanceof Error ? e.message : String(e));
      });
    return () => {
      cancelado = true;
    };
  }, [session.token, projectId]);

  async function handleCreateLink(event: FormEvent) {
    event.preventDefault();
    setCreatingLink(true);
    setError(null);
    try {
      const dias = newLinkExpires.trim() ? Number(newLinkExpires) : null;
      const invitacion = await createInvitation(session.token, projectId, newLinkRole, dias);
      setInvitations((prev) => [invitacion, ...(prev ?? [])]);
    } catch (e) {
      setError(e instanceof Error ? e.message : String(e));
    } finally {
      setCreatingLink(false);
    }
  }

  async function handleCopy(inv: Invitation) {
    try {
      await navigator.clipboard.writeText(inv.url);
      setCopiedId(inv.id);
      setTimeout(() => setCopiedId((id) => (id === inv.id ? null : id)), 2000);
    } catch {
      // clipboard puede fallar por permisos del navegador -- no es fatal,
      // el enlace sigue visible para copiar a mano.
    }
  }

  async function handleRevoke(invitationId: string) {
    try {
      await revokeInvitation(session.token, projectId, invitationId);
      setInvitations((prev) =>
        (prev ?? []).map((i) => (i.id === invitationId ? { ...i, active: false, revokedAt: new Date().toISOString() } : i)),
      );
    } catch (e) {
      setError(e instanceof Error ? e.message : String(e));
    }
  }

  async function handleAddMember(event: FormEvent) {
    event.preventDefault();
    setAddingMember(true);
    setError(null);
    try {
      const miembro = await addMember(session.token, projectId, memberEmail.trim(), memberRole);
      setMembers((prev) => [...(prev ?? []), miembro]);
      setMemberEmail("");
    } catch (e) {
      setError(e instanceof Error ? e.message : String(e));
    } finally {
      setAddingMember(false);
    }
  }

  return (
    <Modal title="Compartir proyecto" onClose={onClose}>
      <div className="share-modal">
        {error && <p className="modal__error">⚠ {error}</p>}

        <div className="share-modal__section">
          <h3>Generar enlace de invitación</h3>
          <form className="share-modal__inline-form" onSubmit={handleCreateLink}>
            <select value={newLinkRole} onChange={(e) => setNewLinkRole(e.target.value as ProjectRole)}>
              <option value="EDITOR">Editor</option>
              <option value="VIEWER">Lector</option>
            </select>
            <input
              type="number"
              min={1}
              max={365}
              placeholder="días (opcional)"
              value={newLinkExpires}
              onChange={(e) => setNewLinkExpires(e.target.value)}
            />
            <button type="submit" className="modal__primary" disabled={creatingLink}>
              {creatingLink ? "Generando…" : "Generar"}
            </button>
          </form>

          {invitations === null && <p className="share-modal__empty">Cargando…</p>}
          {invitations && invitations.length === 0 && (
            <p className="share-modal__empty">Todavía no generaste ningún enlace.</p>
          )}
          {invitations && invitations.length > 0 && (
            <div className="share-modal__list">
              {invitations.map((inv) => {
                const estado = estadoDe(inv);
                return (
                  <div
                    key={inv.id}
                    className={`share-modal__list-item${estado.inactivo ? " share-modal__list-item--inactive" : ""}`}
                  >
                    <div className="share-modal__list-item-info">
                      <span className="share-modal__list-item-name">
                        {rolEnCastellano(inv.role)} — {estado.texto}
                      </span>
                      <span className="share-modal__list-item-meta">
                        {inv.expiresAt
                          ? `vence ${fechaCorta(inv.expiresAt)}`
                          : "sin vencimiento"}
                      </span>
                    </div>
                    {estado.inactivo ? (
                      <span className="share-modal__list-item-meta">—</span>
                    ) : (
                      <>
                        <button type="button" onClick={() => handleCopy(inv)}>
                          {copiedId === inv.id ? "¡Copiado!" : "Copiar enlace"}
                        </button>
                        <button
                          type="button"
                          className="share-modal__revoke"
                          onClick={() => handleRevoke(inv.id)}
                        >
                          Revocar
                        </button>
                      </>
                    )}
                  </div>
                );
              })}
            </div>
          )}
        </div>

        <div className="share-modal__section">
          <h3>Quién tiene acceso</h3>
          {members === null && <p className="share-modal__empty">Cargando…</p>}
          {members && (
            <div className="share-modal__list">
              {members.map((m) => (
                <div key={m.userId} className="share-modal__list-item">
                  <div className="share-modal__list-item-info">
                    <span className="share-modal__list-item-name">{m.fullName}</span>
                    <span className="share-modal__list-item-meta">{m.email}</span>
                  </div>
                  <span className="access-role-badge">{rolEnCastellano(m.role)}</span>
                </div>
              ))}
            </div>
          )}
        </div>

        <div className="share-modal__section">
          <h3>Agregar por correo</h3>
          <form className="share-modal__inline-form" onSubmit={handleAddMember}>
            <input
              type="email"
              placeholder="correo@ejemplo.com"
              value={memberEmail}
              onChange={(e) => setMemberEmail(e.target.value)}
              required
            />
            <select value={memberRole} onChange={(e) => setMemberRole(e.target.value as ProjectRole)}>
              <option value="EDITOR">Editor</option>
              <option value="VIEWER">Lector</option>
            </select>
            <button type="submit" className="modal__primary" disabled={addingMember}>
              {addingMember ? "Agregando…" : "Agregar"}
            </button>
          </form>
        </div>
      </div>
    </Modal>
  );
}
