package main

import (
	"encoding/json"
	"os"
	"sync"
)

// RecentStore guarda, por conversa, o unix (segundos) da última atividade.
// Chave: número (só dígitos) para contatos; JID completo ("...@g.us") para grupos.
type RecentStore struct {
	mu    sync.Mutex
	path  string
	m     map[string]int64
	dirty bool
}

// NewRecentStore lê recent.json se existir; erro de leitura/JSON = mapa vazio.
func NewRecentStore(dir string) *RecentStore {
	rs := &RecentStore{
		path: dir + "/recent.json",
		m:    make(map[string]int64),
	}
	// tenta ler arquivo existente
	data, err := os.ReadFile(rs.path)
	if err != nil {
		// arquivo não existe ou erro de leitura — começa vazio
		return rs
	}
	// tenta parsear JSON
	var stored map[string]int64
	if err := json.Unmarshal(data, &stored); err != nil {
		// JSON inválido — ignora e começa vazio
		return rs
	}
	rs.m = stored
	return rs
}

// Touch registra (ou atualiza) a última atividade de uma conversa.
// Ignora key vazia ou ts <= 0. Guarda o MAIOR ts.
func (r *RecentStore) Touch(key string, ts int64) {
	if key == "" || ts <= 0 {
		return
	}
	r.mu.Lock()
	defer r.mu.Unlock()
	if existing, ok := r.m[key]; !ok || ts > existing {
		r.m[key] = ts
		r.dirty = true
	}
}

// Get devolve o timestamp da última atividade de uma conversa (0 se não existe).
func (r *RecentStore) Get(key string) int64 {
	r.mu.Lock()
	defer r.mu.Unlock()
	return r.m[key]
}

// SaveIfDirty grava em recent.json.tmp + os.Rename (atômico), perms 0o600.
// Só grava se dirty == true.
func (r *RecentStore) SaveIfDirty() error {
	r.mu.Lock()
	if !r.dirty {
		r.mu.Unlock()
		return nil
	}
	// cópia para não bloquear enquanto marshala
	m := make(map[string]int64)
	for k, v := range r.m {
		m[k] = v
	}
	r.dirty = false
	r.mu.Unlock()

	data, err := json.Marshal(m)
	if err != nil {
		return err
	}

	// escreve em .tmp
	tmp := r.path + ".tmp"
	if err := os.WriteFile(tmp, data, 0o600); err != nil {
		return err
	}

	// move atomicamente
	return os.Rename(tmp, r.path)
}
