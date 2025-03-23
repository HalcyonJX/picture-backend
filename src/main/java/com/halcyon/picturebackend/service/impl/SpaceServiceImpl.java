package com.halcyon.picturebackend.service.impl;

import cn.hutool.core.collection.CollUtil;
import cn.hutool.core.util.ObjUtil;
import cn.hutool.core.util.StrUtil;
import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.halcyon.picturebackend.exception.BusinessException;
import com.halcyon.picturebackend.exception.ErrorCode;
import com.halcyon.picturebackend.exception.ThrowUtils;
import com.halcyon.picturebackend.model.dto.space.SpaceAddRequest;
import com.halcyon.picturebackend.model.dto.space.SpaceQueryRequest;
import com.halcyon.picturebackend.model.entity.Space;
import com.halcyon.picturebackend.model.entity.User;
import com.halcyon.picturebackend.model.enums.SpaceLevelEnum;
import com.halcyon.picturebackend.model.vo.SpaceVO;
import com.halcyon.picturebackend.model.vo.UserVO;
import com.halcyon.picturebackend.service.SpaceService;
import com.halcyon.picturebackend.mapper.SpaceMapper;
import com.halcyon.picturebackend.service.UserService;
import org.springframework.beans.BeanUtils;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

import javax.annotation.Resource;
import javax.servlet.http.HttpServletRequest;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;

/**
* @author 张嘉鑫
* @description 针对表【space(空间)】的数据库操作Service实现
* @createDate 2025-03-23 11:19:20
*/
@Service
public class SpaceServiceImpl extends ServiceImpl<SpaceMapper, Space>
    implements SpaceService{

    @Resource
    private UserService userService;

    @Resource
    private TransactionTemplate transactionTemplate;

    /**
     * 创建空间
     * @param spaceAddRequest
     * @param loginUser
     * @return
     */
    @Override
    public long addSpace(SpaceAddRequest spaceAddRequest, User loginUser) {
        //1.填充参数默认值
        //转换实体类和DTO
        Space space = new Space();
        BeanUtils.copyProperties(spaceAddRequest,space);
        if(StrUtil.isBlank(space.getSpaceName())){
            space.setSpaceName("默认空间");
        }
        if(space.getSpaceLevel() == null){
            space.setSpaceLevel(SpaceLevelEnum.COMMON.getValue());
        }
        //填充容量和大小
        this.fillSpaceBySpaceLevel(space);
        //2.校验参数
        this.validSpace(space,true);
        //3.校验权限，非管理员只能创建普通级别空间
        Long userId = loginUser.getId();
        space.setUserId(userId);
        if (SpaceLevelEnum.COMMON.getValue() != space.getSpaceLevel() && !userService.isAdmin(loginUser)) {
            throw new BusinessException(ErrorCode.NO_AUTH_ERROR, "无权限创建指定级别的空间");
        }
        //4.控制同一用户只能创建一个私有空间
        String lock = String.valueOf(userId).intern();
        synchronized (lock){
            Long newSpaceId = transactionTemplate.execute(status -> {
               //判断是否已经有空间
               boolean exists = this.lambdaQuery()
                       .eq(Space::getUserId, userId)
                       .exists();
               //如果有空间，就不能创建
                ThrowUtils.throwIf(exists, ErrorCode.PARAMS_ERROR, "用户已经创建空间");
                //创建
                boolean result = this.save(space);
                ThrowUtils.throwIf(!result, ErrorCode.OPERATION_ERROR, "创建空间失败");
                //返回写入的数据id
                return space.getId();
            });
            return Optional.ofNullable(newSpaceId).orElse(-1L);
        }
    }

    @Override
    public void validSpace(Space space, boolean add) {
        ThrowUtils.throwIf(space == null, ErrorCode.PARAMS_ERROR);
        //从对象中取值
        String spaceName = space.getSpaceName();
        Integer spaceLevel = space.getSpaceLevel();
        SpaceLevelEnum spaceLevelEnum = SpaceLevelEnum.getEnumByValue(spaceLevel);
        //要创建
        if(add){
            if(StrUtil.isBlank(spaceName)){
                throw new BusinessException(ErrorCode.PARAMS_ERROR, "空间名不能为空");
            }
            if(spaceLevelEnum == null){
                throw new BusinessException(ErrorCode.PARAMS_ERROR, "空间级别不能为空");
            }
        }
        //修改数据时，如果要改空间级别
        if(spaceLevel != null && spaceLevelEnum == null){
            throw new BusinessException(ErrorCode.PARAMS_ERROR, "空间等级不存在");
        }
        if(StrUtil.isNotBlank(spaceName) && spaceName.length() > 30){
            throw new BusinessException(ErrorCode.PARAMS_ERROR, "空间名称过长");
        }
    }

    @Override
    public SpaceVO getSpaceVO(Space space, HttpServletRequest request) {
        // 对象转封装类
        SpaceVO spaceVO = SpaceVO.objToVo(space);
        // 关联查询用户信息
        Long userId = space.getUserId();
        if (userId != null && userId > 0) {
            User user = userService.getById(userId);
            UserVO userVO = userService.getUserVO(user);
            spaceVO.setUser(userVO);
        }
        return spaceVO;
    }

    @Override
    public Page<SpaceVO> getSpaceVOPage(Page<Space> spacePage, HttpServletRequest request) {
        List<Space> spaceList = spacePage.getRecords();
        Page<SpaceVO> spaceVOPage = new Page<>(spacePage.getCurrent(), spacePage.getSize(), spacePage.getTotal());
        if (CollUtil.isEmpty(spaceList)) {
            return spaceVOPage;
        }
        // 对象列表 => 封装对象列表
        List<SpaceVO> spaceVOList = spaceList.stream()
                .map(SpaceVO::objToVo)
                .collect(Collectors.toList());
        // 1. 关联查询用户信息
        // 1,2,3,4
        Set<Long> userIdSet = spaceList.stream().map(Space::getUserId).collect(Collectors.toSet());
        // 1 => user1, 2 => user2
        Map<Long, List<User>> userIdUserListMap = userService.listByIds(userIdSet).stream()
                .collect(Collectors.groupingBy(User::getId));
        // 2. 填充信息
        spaceVOList.forEach(spaceVO -> {
            Long userId = spaceVO.getUserId();
            User user = null;
            if (userIdUserListMap.containsKey(userId)) {
                user = userIdUserListMap.get(userId).get(0);
            }
            spaceVO.setUser(userService.getUserVO(user));
        });
        spaceVOPage.setRecords(spaceVOList);
        return spaceVOPage;
    }

    @Override
    public QueryWrapper<Space> getQueryWrapper(SpaceQueryRequest spaceQueryRequest) {
        QueryWrapper<Space> queryWrapper = new QueryWrapper<>();
        if (spaceQueryRequest == null) {
            return queryWrapper;
        }
        // 从对象中取值
        Long id = spaceQueryRequest.getId();
        Long userId = spaceQueryRequest.getUserId();
        String spaceName = spaceQueryRequest.getSpaceName();
        Integer spaceLevel = spaceQueryRequest.getSpaceLevel();
        String sortField = spaceQueryRequest.getSortField();
        String sortOrder = spaceQueryRequest.getSortOrder();
        // 拼接查询条件
        queryWrapper.eq(ObjUtil.isNotEmpty(id), "id", id);
        queryWrapper.eq(ObjUtil.isNotEmpty(userId), "userId", userId);
        queryWrapper.like(StrUtil.isNotBlank(spaceName), "spaceName", spaceName);
        queryWrapper.eq(ObjUtil.isNotEmpty(spaceLevel), "spaceLevel", spaceLevel);
        // 排序
        queryWrapper.orderBy(StrUtil.isNotEmpty(sortField), sortOrder.equals("ascend"), sortField);
        return queryWrapper;
    }

    @Override
    public void fillSpaceBySpaceLevel(Space space) {
        //根据空间级别，自动填充限额
        SpaceLevelEnum spaceLevelEnum = SpaceLevelEnum.getEnumByValue(space.getSpaceLevel());
        if(spaceLevelEnum != null){
            long maxSize = spaceLevelEnum.getMaxSize();
            if(space.getMaxSize() == null){
                space.setMaxSize(maxSize);
            }
            long maxCount = spaceLevelEnum.getMaxCount();
            if(space.getMaxCount() == null){
                space.setMaxCount(maxCount);
            }
        }
    }
}




