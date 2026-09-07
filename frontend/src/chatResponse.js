// ChatResponse.content 是面向用户的降级说明；通用错误响应则回退到 errorMessage/error。
export function chatFailureMessage(data) {
  const candidates = [
    ...(data?.success === false ? [data.content] : []),
    data?.errorMessage,
    data?.error,
    data?.message
  ]
  return (
    candidates.find((value) => typeof value === 'string' && value.trim())?.trim() || '接口请求失败'
  )
}
