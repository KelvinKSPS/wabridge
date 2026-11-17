package main

import (
	"os"
	"testing"
)

func TestRecentStoreTouchKeepsMaxTimestamp(t *testing.T) {
	dir := t.TempDir()
	rs := NewRecentStore(dir)

	// Touch with a lower timestamp.
	rs.Touch("123", 1000)
	if rs.Get("123") != 1000 {
		t.Errorf("esperado 1000, got %d", rs.Get("123"))
	}

	// Touch with a higher timestamp — it should overwrite.
	rs.Touch("123", 2000)
	if rs.Get("123") != 2000 {
		t.Errorf("esperado 2000, got %d", rs.Get("123"))
	}

	// Touch with a lower timestamp — it should be ignored.
	rs.Touch("123", 1500)
	if rs.Get("123") != 2000 {
		t.Errorf("esperado 2000 (mantém máximo), got %d", rs.Get("123"))
	}
}

func TestRecentStoreIgnoresEmptyKeyAndInvalidTimestamp(t *testing.T) {
	dir := t.TempDir()
	rs := NewRecentStore(dir)

	// Ignore Touch with an empty key.
	rs.Touch("", 1000)
	if rs.Get("") != 0 {
		t.Errorf("key vazia deve ser ignorada")
	}

	// Ignore Touch with a timestamp <= 0.
	rs.Touch("456", 0)
	rs.Touch("789", -100)
	if rs.Get("456") != 0 || rs.Get("789") != 0 {
		t.Errorf("ts <= 0 deve ser ignorado")
	}
}

func TestRecentStoreSaveAndReload(t *testing.T) {
	dir := t.TempDir()
	rs := NewRecentStore(dir)

	// Record some conversations.
	rs.Touch("111", 10000)
	rs.Touch("222@g.us", 20000)
	rs.Touch("333", 15000)

	// Save.
	if err := rs.SaveIfDirty(); err != nil {
		t.Fatalf("SaveIfDirty falhou: %v", err)
	}

	// Create a new instance and read the file.
	rs2 := NewRecentStore(dir)
	if rs2.Get("111") != 10000 {
		t.Errorf("após reload, 111 esperado 10000, got %d", rs2.Get("111"))
	}
	if rs2.Get("222@g.us") != 20000 {
		t.Errorf("após reload, 222@g.us esperado 20000, got %d", rs2.Get("222@g.us"))
	}
	if rs2.Get("333") != 15000 {
		t.Errorf("após reload, 333 esperado 15000, got %d", rs2.Get("333"))
	}
}

func TestRecentStoreCorruptedJSONDoesNotCrash(t *testing.T) {
	dir := t.TempDir()
	jsonPath := dir + "/recent.json"

	// Write invalid JSON.
	if err := os.WriteFile(jsonPath, []byte("{invalid}"), 0o600); err != nil {
		t.Fatalf("erro ao escrever JSON inválido: %v", err)
	}

	// NewRecentStore should return an empty map without crashing.
	rs := NewRecentStore(dir)
	if len(rs.m) != 0 {
		t.Errorf("JSON corrompido deve resultar em mapa vazio, got %d items", len(rs.m))
	}

	// It should still work normally.
	rs.Touch("123", 100)
	if rs.Get("123") != 100 {
		t.Errorf("após JSON corrompido, operação normal falhou")
	}
}

func TestRecentStoreSaveIfDirtyDoesNotSaveWhenNotDirty(t *testing.T) {
	dir := t.TempDir()
	rs := NewRecentStore(dir)

	// Do not touch anything; attempt to save.
	if err := rs.SaveIfDirty(); err != nil {
		t.Fatalf("SaveIfDirty falhou: %v", err)
	}

	// The file should not exist.
	_, err := os.Stat(dir + "/recent.json")
	if err == nil {
		t.Errorf("arquivo não deveria ser criado se dirty=false")
	}
}
