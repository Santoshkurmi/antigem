package quota

import (
	"bytes"
	"encoding/json"
	"fmt"
	"io"
	"net/http"
	"os"
	"strings"
	"sync"
	"time"

	"gemini-server/pkg/models"
)

var (
	quotaCacheMu    sync.RWMutex
	cachedQuotas    *models.QuotaSummaryResponse
	lastFetchTime   time.Time
	isFetchingQuota bool
)

// GetLocalAccessToken reads the OAuth token from disk.
func GetLocalAccessToken(tokenFile string) string {
	data, err := os.ReadFile(tokenFile)
	if err != nil {
		return ""
	}

	var parsed struct {
		Token interface{} `json:"token"`
	}
	if err := json.Unmarshal(data, &parsed); err != nil {
		return ""
	}

	if s, ok := parsed.Token.(string); ok {
		return s
	}
	if m, ok := parsed.Token.(map[string]interface{}); ok {
		if at, ok2 := m["access_token"].(string); ok2 {
			return at
		}
	}
	return ""
}

type apiQuotaBucket struct {
	BucketID          string  `json:"bucketId"`
	Window            string  `json:"window"`
	DisplayName       string  `json:"displayName"`
	RemainingFraction float64 `json:"remainingFraction"`
	ResetTime         string  `json:"resetTime"`
	Description       string  `json:"description"`
}

type apiQuotaGroup struct {
	DisplayName string           `json:"displayName"`
	Description string           `json:"description"`
	Buckets     []apiQuotaBucket `json:"buckets"`
}

type apiQuotaResponse struct {
	Groups []apiQuotaGroup `json:"groups"`
}

// FetchQuotaSummary retrieves quota metrics from Google CloudCode PA API.
func FetchQuotaSummary(tokenFile string, force bool) *models.QuotaSummaryResponse {
	quotaCacheMu.RLock()
	if !force && cachedQuotas != nil && time.Since(lastFetchTime) < 30*time.Second {
		defer quotaCacheMu.RUnlock()
		return cachedQuotas
	}
	quotaCacheMu.RUnlock()

	quotaCacheMu.Lock()
	if isFetchingQuota {
		quotaCacheMu.Unlock()
		return cachedQuotas
	}
	isFetchingQuota = true
	quotaCacheMu.Unlock()

	defer func() {
		quotaCacheMu.Lock()
		isFetchingQuota = false
		quotaCacheMu.Unlock()
	}()

	accessToken := GetLocalAccessToken(tokenFile)
	if accessToken == "" {
		return cachedQuotas
	}

	req, err := http.NewRequest("POST", "https://cloudcode-pa.googleapis.com/v1internal:retrieveUserQuotaSummary", bytes.NewBuffer([]byte("{}")))
	if err != nil {
		return cachedQuotas
	}

	req.Header.Set("Authorization", "Bearer "+accessToken)
	req.Header.Set("Content-Type", "application/json")
	req.Header.Set("User-Agent", "Antigravity/1.0")

	client := &http.Client{Timeout: 10 * time.Second}
	resp, err := client.Do(req)
	if err != nil || resp.StatusCode != http.StatusOK {
		return cachedQuotas
	}
	defer resp.Body.Close()

	bodyBytes, err := io.ReadAll(resp.Body)
	if err != nil {
		return cachedQuotas
	}

	var apiRes apiQuotaResponse
	if err := json.Unmarshal(bodyBytes, &apiRes); err != nil {
		return cachedQuotas
	}

	now := time.Now()
	var groups []models.QuotaGroupItem

	for _, g := range apiRes.Groups {
		isGemini := strings.Contains(strings.ToLower(g.DisplayName), "gemini")
		groupId := "claude_gpt"
		groupName := "Anthropic Claude & GPT Models"
		if isGemini {
			groupId = "gemini"
			groupName = "Google Gemini Models"
		}
		if g.DisplayName != "" {
			groupName = g.DisplayName
		}

		var fiveHour *models.BucketInfo
		var weekly *models.BucketInfo

		for _, b := range g.Buckets {
			diff := time.Duration(0)
			if b.ResetTime != "" {
				if t, err := time.Parse(time.RFC3339, b.ResetTime); err == nil && t.After(now) {
					diff = t.Sub(now)
				}
			}

			days := int(diff.Hours()) / 24
			hours := int(diff.Hours()) % 24
			mins := int(diff.Minutes()) % 60

			countdown := fmt.Sprintf("%dm", mins)
			if days > 0 {
				countdown = fmt.Sprintf("%dd %dh", days, hours)
			} else if hours > 0 {
				countdown = fmt.Sprintf("%dh %dm", hours, mins)
			}

			remFrac := b.RemainingFraction
			if remFrac == 0 && b.ResetTime == "" {
				remFrac = 1.0
			}

			remPct := fmt.Sprintf("%.1f%%", remFrac*100)
			usedPct := fmt.Sprintf("%.1f%%", (1.0-remFrac)*100)

			window := b.Window
			if window == "" {
				if strings.Contains(b.BucketID, "5h") {
					window = "5h"
				} else {
					window = "weekly"
				}
			}

			bInfo := &models.BucketInfo{
				Window:            window,
				DisplayName:       b.DisplayName,
				RemainingFraction: remFrac,
				RemainingPct:      remPct,
				UsedPct:           usedPct,
				ResetTime:         b.ResetTime,
				Countdown:         countdown,
				Description:       b.Description,
			}

			if window == "5h" {
				fiveHour = bInfo
			} else {
				weekly = bInfo
			}
		}

		groups = append(groups, models.QuotaGroupItem{
			GroupID:     groupId,
			GroupName:   groupName,
			Description: g.Description,
			FiveHour:    fiveHour,
			Weekly:      weekly,
		})
	}

	result := &models.QuotaSummaryResponse{
		Groups:      groups,
		LastUpdated: now.UTC().Format(time.RFC3339),
	}

	quotaCacheMu.Lock()
	cachedQuotas = result
	lastFetchTime = now
	quotaCacheMu.Unlock()

	return result
}
