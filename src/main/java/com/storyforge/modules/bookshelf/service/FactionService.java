package com.storyforge.modules.bookshelf.service;

import com.storyforge.modules.bookshelf.dto.FactionMemberRequest;
import com.storyforge.modules.bookshelf.dto.FactionMemberVO;
import com.storyforge.modules.bookshelf.dto.FactionRequest;
import com.storyforge.modules.bookshelf.dto.FactionVO;

import java.util.List;

/**
 * 势力服务：势力的增删改查与成员（职位）管理。
 */
public interface FactionService {

    List<FactionVO> list(Long novelId);

    FactionVO create(Long novelId, FactionRequest request);

    FactionVO update(Long factionId, FactionRequest request);

    void delete(Long factionId);

    /** 添加成员：同一角色在同一势力中只能有一条任职记录。 */
    FactionMemberVO addMember(Long factionId, FactionMemberRequest request);

    FactionMemberVO updateMember(Long memberId, String position);

    void removeMember(Long memberId);
}
