package main

import (
	"encoding/json"
	"os"
	"path/filepath"
	"strings"
	"sync"
)

// Config is the daemon's local configuration, persisted as JSON in the app's data directory.
type Config struct {
	// AllowedNumbers is the allowlist of senders (empty = everyone).
	AllowedNumbers []string `json:"allowedNumbers"`
	// AcceptAll disables filtering (equivalent to an empty list).
	AcceptAll bool `json:"acceptAll"`
}

// ConfigStore stores the configuration on disk.
type ConfigStore struct {
	mu   sync.RWMutex
	path string
	cfg  Config
}

// NewConfigStore loads (or creates) the configuration in the directory.
func NewConfigStore(dir string) (*ConfigStore, error) {
	s := &ConfigStore{path: filepath.Join(dir, "config.json")}
	if err := os.MkdirAll(dir, 0o700); err != nil {
		return nil, err
	}
	data, err := os.ReadFile(s.path)
	if err == nil {
		_ = json.Unmarshal(data, &s.cfg)
	} else if !os.IsNotExist(err) {
		return nil, err
	}
	return s, nil
}

// Allowed returns the list of allowed numbers (nil = everyone).
func (s *ConfigStore) Allowed() []string {
	s.mu.RLock()
	defer s.mu.RUnlock()
	if s.cfg.AcceptAll {
		return nil
	}
	out := make([]string, 0, len(s.cfg.AllowedNumbers))
	for _, n := range s.cfg.AllowedNumbers {
		out = append(out, strings.TrimSpace(n))
	}
	return out
}

// SetAllowed saves the allowlist.
func (s *ConfigStore) SetAllowed(numbers []string, acceptAll bool) error {
	s.mu.Lock()
	defer s.mu.Unlock()
	clean := make([]string, 0, len(numbers))
	for _, n := range numbers {
		n = strings.TrimSpace(n)
		if n != "" {
			clean = append(clean, n)
		}
	}
	s.cfg = Config{AllowedNumbers: clean, AcceptAll: acceptAll}
	data, err := json.MarshalIndent(s.cfg, "", "  ")
	if err != nil {
		return err
	}
	return os.WriteFile(s.path, data, 0o600)
}

// IsAllowed reports whether the number is allowed.
//
// Uses the same rule as the Node server: an empty list means everyone;
// a non-empty list allows only exact numbers or numbers ending in a listed suffix.
func IsAllowed(allowed []string, number string) bool {
	if len(allowed) == 0 {
		return true
	}
	n := onlyDigits(number)
	if n == "" {
		return false
	}
	for _, a := range allowed {
		a = onlyDigits(a)
		if a == "" {
			continue
		}
		if a == n || strings.HasSuffix(n, a) {
			return true
		}
	}
	return false
}

// onlyDigits removes everything that is not a digit.
func onlyDigits(v string) string {
	var b strings.Builder
	for _, r := range v {
		if r >= '0' && r <= '9' {
			b.WriteRune(r)
		}
	}
	return b.String()
}
