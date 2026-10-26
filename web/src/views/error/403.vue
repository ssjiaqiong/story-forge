<template>
  <div class="page-container">
    <el-card shadow="never">
      <el-result icon="warning" title="403" sub-title="当前账号没有访问该页面的权限">
        <template #extra>
          <el-button type="primary" @click="router.replace('/dashboard')">返回首页</el-button>
        </template>
      </el-result>
    </el-card>
  </div>
</template>

<script setup lang="ts">
import { useRouter } from 'vue-router'

const router = useRouter()
</script>
