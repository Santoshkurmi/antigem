package query

import (
	"encoding/json"

	"github.com/itchyny/gojq"
)

func jqTruthy(b []byte, code *gojq.Code) bool {
	var v any
	if json.Unmarshal(b, &v) != nil {
		return false
	}
	iter := code.Run(v)
	r, ok := iter.Next()
	if !ok {
		return false
	}
	switch x := r.(type) {
	case error, nil:
		return false
	case bool:
		return x
	}
	return true
}
