package main

import (
	"encoding/json"
	"os"
	"sync"
)

// RecentStore stores the Unix timestamp (seconds) of the last activity per conversation.
// Key: number (digits only) for contacts; full JID ("...@g.us") for groups.
type RecentStore struct {
	mu    sync.Mutex
	path  string
	m     map[string]int64
	dirty bool
}

// NewRecentStore reads recent.json if present; read/JSON error = empty map.
func NewRecentStore(dir string) *RecentStore {
	rs := &RecentStore{
		path: dir + "/recent.json",
		m:    make(map[string]int64),
	}
	// Try to read the existing file
	data, err := os.ReadFile(rs.path)
	if err != nil {
		// File does not exist or read error — start empty
		return rs
	}
	// Try to parse JSON
	var stored map[string]int64
	if err := json.Unmarshal(data, &stored); err != nil {
		// Invalid JSON — ignore it and start empty
		return rs
	}
	rs.m = stored
	return rs
}

// Touch records (or updates) the last activity of a conversation.
// Ignores empty key or ts <= 0. Keeps the LARGEST ts.
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

// Get returns the last activity timestamp of a conversation (0 if none exists).
func (r *RecentStore) Get(key string) int64 {
	r.mu.Lock()
	defer r.mu.Unlock()
	return r.m[key]
}

// SaveIfDirty writes to recent.json.tmp + os.Rename (atomic), permissions 0o600.
// Writes only if dirty == true.
func (r *RecentStore) SaveIfDirty() error {
	r.mu.Lock()
	if !r.dirty {
		r.mu.Unlock()
		return nil
	}
	// Copy so we do not block while marshaling
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

	// Write to .tmp
	tmp := r.path + ".tmp"
	if err := os.WriteFile(tmp, data, 0o600); err != nil {
		return err
	}

	// Move atomically
	return os.Rename(tmp, r.path)
}
